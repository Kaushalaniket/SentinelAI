"""Alert management: filtering, search, status updates and statistics."""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, HTTPException, Query

from ..db import get_db, loads, utcnow
from ..ml.labels import SEVERITY_ORDER, category_label
from ..schemas import AlertUpdateRequest
from ..settings import show_simulated

router = APIRouter(prefix="/alerts", tags=["alerts"])

VALID_STATUSES = {"open", "acknowledged", "resolved"}
VALID_SEVERITIES = set(SEVERITY_ORDER)


def _decorate(row: dict[str, Any]) -> dict[str, Any]:
    cat = row.get("category")
    row["category_label"] = category_label(cat) if cat else "Uncategorised"
    row["severity_rank"] = (
        SEVERITY_ORDER.index(row["severity"]) if row["severity"] in SEVERITY_ORDER else 99
    )
    row["is_simulated"] = row.get("origin") == "simulated"
    row["origin_label"] = (
        "Simulated demo" if row.get("origin") == "simulated" else "Model detection"
    )
    row["occurred_at"] = row.get("occurred_at") or row.get("created_at")
    return row


@router.get("")
def list_alerts(
    page: int = Query(1, ge=1),
    page_size: int = Query(25, ge=1, le=200),
    severity: str | None = Query(None, description="critical|high|medium|low|info"),
    status: str | None = Query(None, description="open|acknowledged|resolved"),
    origin: Literal["model", "simulated"] | None = None,
    category: str | None = None,
    q: str | None = Query(None, max_length=120, description="free-text search"),
    date_from: str | None = Query(None, description="ISO date, inclusive"),
    date_to: str | None = Query(None, description="ISO date, inclusive"),
    sort: Literal["newest", "oldest", "severity"] = "newest",
) -> dict:
    """Paginated alert console with combined filters."""
    where: list[str] = []
    params: list[Any] = []

    if not show_simulated():
        where.append("origin <> 'simulated'")
    if severity:
        if severity not in VALID_SEVERITIES:
            raise HTTPException(422, f"severity must be one of {sorted(VALID_SEVERITIES)}")
        where.append("severity = ?")
        params.append(severity)
    if status:
        if status not in VALID_STATUSES:
            raise HTTPException(422, f"status must be one of {sorted(VALID_STATUSES)}")
        where.append("status = ?")
        params.append(status)
    if origin:
        where.append("origin = ?")
        params.append(origin)
    if category:
        where.append("category = ?")
        params.append(category)
    if q:
        like = f"%{q.strip()}%"
        where.append(
            "(title LIKE ? OR description LIKE ? OR source_ip LIKE ? "
            "OR destination_ip LIKE ? OR category LIKE ?)"
        )
        params.extend([like] * 5)
    if date_from:
        where.append("COALESCE(occurred_at, created_at) >= ?")
        params.append(date_from if "T" in date_from else f"{date_from}T00:00:00+00:00")
    if date_to:
        where.append("COALESCE(occurred_at, created_at) <= ?")
        params.append(date_to if "T" in date_to else f"{date_to}T23:59:59+00:00")

    clause = (" WHERE " + " AND ".join(where)) if where else ""
    order = {
        "newest": "COALESCE(occurred_at, created_at) DESC, id DESC",
        "oldest": "COALESCE(occurred_at, created_at) ASC, id ASC",
        "severity": "severity_rank, COALESCE(occurred_at, created_at) DESC",
    }[sort]
    if sort == "severity":
        order = (
            "CASE severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 "
            "WHEN 'medium' THEN 2 WHEN 'low' THEN 3 ELSE 4 END, "
            "COALESCE(occurred_at, created_at) DESC"
        )

    with get_db() as conn:
        total = conn.execute(f"SELECT COUNT(*) AS n FROM alerts{clause}", params).fetchone()["n"]
        offset = (page - 1) * page_size
        rows = [
            dict(r)
            for r in conn.execute(
                f"SELECT * FROM alerts{clause} ORDER BY {order} LIMIT ? OFFSET ?",
                [*params, page_size, offset],
            )
        ]

    return {
        "items": [_decorate(r) for r in rows],
        "page": page,
        "page_size": page_size,
        "total": total,
        "pages": max(1, -(-total // page_size)),
    }


@router.get("/stats")
def alert_stats() -> dict:
    """Aggregates for the filter chips and severity chart."""
    with get_db() as conn:
        sim_clause = "" if show_simulated() else " WHERE origin <> 'simulated'"
        by_severity = {s: 0 for s in SEVERITY_ORDER}
        for r in conn.execute(
            f"SELECT severity, COUNT(*) AS n FROM alerts{sim_clause} GROUP BY severity"
        ):
            by_severity[r["severity"]] = r["n"]

        by_status = {
            r["status"]: r["n"]
            for r in conn.execute(
                f"SELECT status, COUNT(*) AS n FROM alerts{sim_clause} GROUP BY status"
            )
        }
        by_origin = {
            r["origin"]: r["n"]
            for r in conn.execute(
                f"SELECT origin, COUNT(*) AS n FROM alerts{sim_clause} GROUP BY origin"
            )
        }
        by_category = {
            r["category"]: r["n"]
            for r in conn.execute(
                f"SELECT category, COUNT(*) AS n FROM alerts{sim_clause} "
                f"GROUP BY category ORDER BY n DESC"
            )
        }
        by_day = [
            dict(r)
            for r in conn.execute(
                "SELECT substr(COALESCE(occurred_at, created_at), 1, 10) AS day, "
                "COUNT(*) AS total, "
                "SUM(CASE WHEN severity IN ('critical','high') THEN 1 ELSE 0 END) AS severe "
                f"FROM alerts{sim_clause} GROUP BY day ORDER BY day DESC LIMIT 30"
            )
        ]
        total = conn.execute(
            f"SELECT COUNT(*) AS n FROM alerts{sim_clause}"
        ).fetchone()["n"]

    return {
        "total": total,
        "by_severity": by_severity,
        "by_status": by_status,
        "by_origin": by_origin,
        "by_category": {k: v for k, v in by_category.items()},
        "by_day": list(reversed(by_day)),
        "severity_order": SEVERITY_ORDER,
    }


@router.get("/{alert_id}")
def alert_detail(alert_id: int) -> dict:
    with get_db() as conn:
        row = conn.execute("SELECT * FROM alerts WHERE id = ?", (alert_id,)).fetchone()
        if row is None:
            raise HTTPException(404, f"Alert {alert_id} not found")
        alert = dict(row)
        record = None
        if alert.get("record_id"):
            rec = conn.execute(
                "SELECT * FROM traffic_records WHERE id = ?", (alert["record_id"],)
            ).fetchone()
            if rec:
                record = dict(rec)
                record["features"] = loads(record.get("features"), None)
        prediction = None
        if alert.get("prediction_id"):
            p = conn.execute(
                "SELECT * FROM predictions WHERE id = ?", (alert["prediction_id"],)
            ).fetchone()
            if p:
                prediction = dict(p)
                prediction["probabilities"] = loads(prediction.get("probabilities"), {})
                prediction["predicted_label"] = category_label(
                    prediction["predicted_category"]
                )
        history = [
            dict(r)
            for r in conn.execute(
                "SELECT * FROM alerts WHERE COALESCE(source_ip, '') = ? "
                "AND COALESCE(destination_ip, '') = ? AND id <> ? "
                "ORDER BY COALESCE(occurred_at, created_at) DESC LIMIT 20",
                (
                    alert.get("source_ip") or "",
                    alert.get("destination_ip") or "",
                    alert_id,
                ),
            )
        ]

    return {
        "alert": _decorate(alert),
        "traffic_record": record,
        "prediction": prediction,
        "related_alerts": [_decorate(h) for h in history],
    }


@router.patch("/{alert_id}")
def update_alert(alert_id: int, body: AlertUpdateRequest) -> dict:
    """Acknowledge, resolve or reopen an alert."""
    with get_db() as conn:
        row = conn.execute("SELECT * FROM alerts WHERE id = ?", (alert_id,)).fetchone()
        if row is None:
            raise HTTPException(404, f"Alert {alert_id} not found")
        now = utcnow()
        conn.execute(
            "UPDATE alerts SET status = ?, updated_at = ? WHERE id = ?",
            (body.status, now, alert_id),
        )
        updated = dict(conn.execute("SELECT * FROM alerts WHERE id = ?", (alert_id,)).fetchone())
    return {"status": "ok", "alert": _decorate(updated)}


@router.post("/{alert_id}/acknowledge")
def acknowledge(alert_id: int) -> dict:
    """Convenience endpoint used by the bulk 'Acknowledge all' action."""
    with get_db() as conn:
        row = conn.execute("SELECT id FROM alerts WHERE id = ?", (alert_id,)).fetchone()
        if row is None:
            raise HTTPException(404, f"Alert {alert_id} not found")
        conn.execute(
            "UPDATE alerts SET status = 'acknowledged', updated_at = ? WHERE id = ?",
            (utcnow(), alert_id),
        )
    return {"status": "ok", "id": alert_id, "new_status": "acknowledged"}
