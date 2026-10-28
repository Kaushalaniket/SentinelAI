"""Traffic Analysis endpoints: browse, filter, inspect and import flow records."""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, HTTPException, Query

from .. import config
from ..db import get_db, loads
from ..ml.labels import CATEGORY_META, category_label, category_severity
from ..schemas import TrafficGenerateRequest
from ..settings import show_simulated
from ..seed import (
    SEED_QUOTAS,
    generate_simulated_traffic,
    load_dataset_records,
    score_records,
)

router = APIRouter(prefix="/traffic", tags=["traffic"])


def _decorate(row: dict[str, Any]) -> dict[str, Any]:
    row["category_label"] = category_label(row.get("category") or "benign")
    row["severity"] = category_severity(row.get("category") or "benign")
    row["is_attack"] = (row.get("category") or "benign") != "benign"
    row["is_simulated"] = row.get("origin") == "simulated"
    row["has_features"] = bool(row.get("features"))
    row["features"] = loads(row.get("features"), None)
    return row


@router.get("")
def list_traffic(
    page: int = Query(1, ge=1),
    page_size: int = Query(25, ge=1, le=200),
    origin: Literal["simulated", "dataset", "upload"] | None = None,
    category: str | None = None,
    severity: str | None = None,
    q: str | None = Query(None, max_length=120, description="IP / port / label search"),
) -> dict:
    """Paginated traffic records with SQL-side filtering and searching."""
    where: list[str] = []
    params: list[Any] = []

    if not show_simulated():
        where.append("origin <> 'simulated'")
    if origin:
        where.append("origin = ?")
        params.append(origin)
    if category:
        where.append("category = ?")
        params.append(category)
    if severity:
        # severity is derived from category, so expand it to the category list
        cats = [c for c, meta in CATEGORY_META.items() if meta["severity"] == severity]
        if not cats:
            where.append("1 = 0")
        else:
            where.append(f"category IN ({','.join('?' * len(cats))})")
            params.extend(cats)
    if q:
        like = f"%{q.strip()}%"
        where.append(
            "(source_ip LIKE ? OR destination_ip LIKE ? OR label LIKE ? "
            "OR CAST(destination_port AS TEXT) LIKE ? OR protocol LIKE ?)"
        )
        params.extend([like] * 5)

    clause = (" WHERE " + " AND ".join(where)) if where else ""

    with get_db() as conn:
        total = conn.execute(
            f"SELECT COUNT(*) AS n FROM traffic_records{clause}", params
        ).fetchone()["n"]
        offset = (page - 1) * page_size
        rows = [
            dict(r)
            for r in conn.execute(
                f"SELECT * FROM traffic_records{clause} "
                f"ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                [*params, page_size, offset],
            )
        ]
        # Attach the model prediction (if one exists) for each page of rows.
        if rows:
            ids = [r["id"] for r in rows]
            marks = ",".join("?" * len(ids))
            preds = {
                p["record_id"]: p
                for p in conn.execute(
                    f"SELECT record_id, predicted_category, confidence, is_attack, "
                    f"model_version, created_at FROM predictions "
                    f"WHERE record_id IN ({marks}) ORDER BY id DESC",
                    ids,
                )
            }
            for r in rows:
                p = preds.get(r["id"])
                r["prediction"] = (
                    {
                        "predicted_category": p["predicted_category"],
                        "predicted_label": category_label(p["predicted_category"]),
                        "confidence": p["confidence"],
                        "is_attack": bool(p["is_attack"]),
                        "model_version": p["model_version"],
                    }
                    if p
                    else None
                )
        else:
            for r in rows:
                r["prediction"] = None

    return {
        "items": [_decorate(r) for r in rows],
        "page": page,
        "page_size": page_size,
        "total": total,
        "pages": max(1, -(-total // page_size)),
    }


@router.get("/summary")
def traffic_summary() -> dict:
    """Counts by origin / category used by the filter chips and charts."""
    with get_db() as conn:
        sim_clause = "" if show_simulated() else " WHERE origin <> 'simulated'"
        by_origin = {
            r["origin"]: r["n"]
            for r in conn.execute(
                f"SELECT origin, COUNT(*) AS n FROM traffic_records{sim_clause} GROUP BY origin"
            )
        }
        by_category = {
            r["category"]: r["n"]
            for r in conn.execute(
                f"SELECT category, COUNT(*) AS n FROM traffic_records{sim_clause} "
                f"GROUP BY category ORDER BY n DESC"
            )
        }
        by_protocol = {
            r["protocol"]: r["n"]
            for r in conn.execute(
                f"SELECT COALESCE(protocol,'UNKNOWN') AS protocol, COUNT(*) AS n "
                f"FROM traffic_records{sim_clause} GROUP BY protocol ORDER BY n DESC"
            )
        }
        scored = conn.execute("SELECT COUNT(*) AS n FROM predictions").fetchone()["n"]
        dataset_rows = conn.execute(
            "SELECT COUNT(*) AS n FROM traffic_records WHERE origin='dataset'"
        ).fetchone()["n"]
    return {
        "total": sum(by_origin.values()),
        "by_origin": by_origin,
        "by_category": by_category,
        "by_protocol": by_protocol,
        "scored": scored,
        "dataset_rows_available": dataset_rows,
        "dataset_zip_present": config.DATASET_ZIP.exists(),
        "simulated_visible": show_simulated(),
    }


@router.post("/simulate")
def simulate(body: TrafficGenerateRequest) -> dict:
    """
    Generate synthetic demo flows.

    Every generated row is stored with ``origin='simulated'`` and the UI shows
    a SIMULATED badge on it.  These rows are never scored by the model.
    """
    if body.count > 0 and not 0 <= body.attack_ratio <= 1:
        raise HTTPException(422, "attack_ratio must be between 0 and 1")
    summary = generate_simulated_traffic(count=body.count, attack_ratio=body.attack_ratio)
    return {"status": "ok", **summary}


@router.post("/load-dataset")
def load_dataset(limit: int | None = Query(None, ge=1, le=5000)) -> dict:
    """
    Import a stratified sample of real CIC-IDS2017 flows into SQLite.

    Requires ``data/raw/GeneratedLabelledFlows.zip`` (see
    ``python -m scripts.download_dataset``).
    """
    quotas = None
    if limit is not None:
        # Scale the default quotas down/up to approximately ``limit`` rows.
        base = sum(SEED_QUOTAS.values())
        scale = limit / base
        quotas = {k: max(1, int(v * scale)) for k, v in SEED_QUOTAS.items()}
    try:
        summary = load_dataset_records(quotas=quotas)
    except FileNotFoundError as exc:
        raise HTTPException(404, str(exc)) from exc

    inference = None
    try:
        inference = score_records(only_without_prediction=True, create_alerts=True)
    except Exception as exc:  # model missing is the expected case here
        inference = {"skipped": True, "reason": str(exc)}
    return {"status": "ok", "import": summary, "inference": inference}


@router.get("/{record_id}")
def traffic_detail(record_id: int) -> dict:
    """One record with its full feature vector and model prediction."""
    with get_db() as conn:
        row = conn.execute(
            "SELECT * FROM traffic_records WHERE id = ?", (record_id,)
        ).fetchone()
        if row is None:
            raise HTTPException(404, f"Traffic record {record_id} not found")
        record = dict(row)
        pred = conn.execute(
            "SELECT * FROM predictions WHERE record_id = ? ORDER BY id DESC LIMIT 1",
            (record_id,),
        ).fetchone()
        related = [
            dict(r)
            for r in conn.execute(
                "SELECT * FROM alerts WHERE record_id = ? ORDER BY created_at DESC",
                (record_id,),
            )
        ]

    record = _decorate(record)
    probabilities = None
    if pred is not None:
        p = dict(pred)
        probabilities = loads(p.get("probabilities"), {})
        p["probabilities"] = probabilities
        p["predicted_label"] = category_label(p["predicted_category"])
        record["prediction"] = p
    else:
        record["prediction"] = None
    record["alerts"] = related
    return record
