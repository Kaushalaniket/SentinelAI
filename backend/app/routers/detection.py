"""AI Detection endpoints: CSV upload, validation, scoring and batch history."""

from __future__ import annotations

import io
import json
import uuid

import numpy as np
import pandas as pd
from fastapi import APIRouter, File, HTTPException, UploadFile

from .. import config
from ..db import get_db, loads, utcnow
from ..ml.labels import category_label, category_severity, is_attack, to_category
from ..ml.predictor import (
    ModelNotTrained,
    model_exists,
    model_info_safe,
    predict_frame,
    predict_single,
)
from ..ml.preprocess import align_features, strip_columns
from ..settings import confidence_threshold
from ..schemas import PredictRowRequest

router = APIRouter(prefix="/detect", tags=["detection"])

MAX_UPLOAD_BYTES = 30 * 1024 * 1024   # 30 MB
MAX_UPLOAD_ROWS = 20_000


def _require_model() -> None:
    if not model_exists():
        raise HTTPException(
            503,
            "No trained model yet. Train it first:  python -m app.ml.train",
        )


# ---------------------------------------------------------------------------
# Schema / sample discovery
# ---------------------------------------------------------------------------
@router.get("/schema")
def detection_schema() -> dict:
    """The exact feature columns the model expects, plus available sample files."""
    info = model_info_safe()
    samples = sorted(config.SAMPLE_DIR.glob("*.csv")) if config.SAMPLE_DIR.exists() else []
    return {
        "model": info,
        "expected_features": info.get("features", []),
        "expected_feature_count": info.get("feature_count", 0),
        "categories": [
            {"key": c, "label": category_label(c), "severity": category_severity(c),
             "is_attack": is_attack(c)}
            for c in info.get("classes", [])
        ],
        "identifier_columns_ignored": sorted(
            ["Flow ID", "Source IP", "Destination IP", "Source Port", "Timestamp",
             "Label", "Comment"]
        ),
        "notes": [
            "Column names are matched case-insensitively after trimming spaces.",
            "Missing model columns are filled with the training median (SimpleImputer).",
            "Cells containing Infinity or blank values are treated as missing.",
            "A file is rejected only when fewer than half of the model features are present.",
        ],
        "samples": [
            {
                "filename": p.name,
                "bytes": p.stat().st_size,
                "url": f"{config.API_PREFIX}/detect/samples/{p.name}",
            }
            for p in samples
        ],
        "limits": {"max_bytes": MAX_UPLOAD_BYTES, "max_rows": MAX_UPLOAD_ROWS},
    }


# ---------------------------------------------------------------------------
# Single-record prediction
# ---------------------------------------------------------------------------
@router.post("/predict")
def predict_record(body: PredictRowRequest) -> dict:
    """Score one hand-written flow record (used by the 'quick check' form)."""
    _require_model()
    try:
        out = predict_single(body.features)
    except ModelNotTrained as exc:
        raise HTTPException(503, str(exc)) from exc
    if not out.get("usable"):
        raise HTTPException(
            422,
            {
                "message": "Not enough recognised feature columns to score this record.",
                "alignment": out["alignment"],
            },
        )
    pred = out["prediction"]
    return {
        "usable": True,
        "model_version": out["model_version"],
        "prediction": pred,
        "severity": category_severity(pred["category"]),
        "alignment": out["alignment"],
    }


# ---------------------------------------------------------------------------
# CSV upload
# ---------------------------------------------------------------------------
@router.post("/upload")
async def upload_csv(file: UploadFile = File(...)) -> dict:
    """
    Validate and score a CIC-IDS2017 style flow CSV.

    The response reports exactly what happened: which columns matched, which
    values were missing, how many rows were accepted, and the per-row
    predictions together with any alerts that were raised.
    """
    _require_model()

    if not (file.filename or "").lower().endswith(".csv"):
        raise HTTPException(415, "Please upload a .csv file.")

    raw = await file.read()
    if len(raw) == 0:
        raise HTTPException(400, "The uploaded file is empty.")
    if len(raw) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            413,
            f"File is {len(raw) / 1e6:.1f} MB; the limit is {MAX_UPLOAD_BYTES / 1e6:.0f} MB.",
        )

    # ---- parse -------------------------------------------------------
    try:
        df = strip_columns(pd.read_csv(io.BytesIO(raw), encoding="latin-1", low_memory=False))
    except Exception as exc:
        raise HTTPException(
            422,
            f"Could not parse the file as CSV: {exc}. "
            "Check that it is comma separated and has a header row.",
        ) from exc

    if df.empty:
        raise HTTPException(422, "The CSV has a header but no data rows.")

    info = model_info_safe()
    if not info.get("available"):
        raise HTTPException(503, info.get("reason", "Model unavailable"))

    expected: list[str] = info["features"]
    label_col = next(
        (c for c in df.columns if c.strip().lower() in {"label", "class", "target"}),
        None,
    )

    matrix, alignment = align_features(df, expected)

    if not alignment["usable"]:
        raise HTTPException(
            422,
            {
                "message": (
                    f"Only {alignment['matched_count']} of "
                    f"{alignment['expected_count']} expected feature columns were found "
                    f"(at least {alignment['required_min_features']} are required)."
                ),
                "alignment": alignment,
                "hint": (
                    "Upload a CSV exported from CICFlowMeter / CIC-IDS2017, or download "
                    "one of the sample files provided on this page."
                ),
            },
        )

    total_rows = len(matrix)
    truncated = total_rows > MAX_UPLOAD_ROWS
    if truncated:
        matrix = matrix.iloc[:MAX_UPLOAD_ROWS]
        df = df.iloc[:MAX_UPLOAD_ROWS]
        total_rows = MAX_UPLOAD_ROWS

    # ---- missing-value statistics (before imputation) -----------------
    values = matrix.to_numpy(dtype=np.float64)
    missing_mask = ~np.isfinite(values)
    missing_cells = int(missing_mask.sum())
    rows_with_missing = int(missing_mask.any(axis=1).sum())
    per_column_missing = {
        col: int(missing_mask[:, i].sum())
        for i, col in enumerate(matrix.columns)
        if missing_mask[:, i].sum() > 0
    }

    # ---- score -------------------------------------------------------
    try:
        out = predict_frame(matrix)
    except ModelNotTrained as exc:
        raise HTTPException(503, str(exc)) from exc
    if not out["usable"]:
        raise HTTPException(422, "Feature alignment failed during scoring.")

    results = out["results"]
    batch_id = uuid.uuid4().hex[:16]
    now = utcnow()
    labels = df[label_col].tolist() if label_col else [None] * total_rows
    attack_rows = sum(1 for r in results if r["is_attack"])

    # ---- persist -----------------------------------------------------
    with get_db() as conn:
        record_ids: list[int] = []
        for i in range(total_rows):
            feat = {k: (None if not np.isfinite(v) else float(v))
                    for k, v in zip(matrix.columns, values[i])}
            label_val = labels[i]
            truth = None
            if label_val is not None and str(label_val).strip():
                label_val = str(label_val).strip()
                truth = to_category(label_val)
            cur = conn.execute(
                """
                INSERT INTO traffic_records (
                    captured_at, source_ip, destination_ip, source_port,
                    destination_port, protocol, flow_duration_ms, total_packets,
                    total_bytes, fwd_bytes, bwd_bytes, packets_per_sec, bytes_per_sec,
                    label, category, origin, origin_detail, batch_id, features, created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    now, None, None,
                    None,
                    int(feat.get("Destination Port") or 0),
                    None,
                    feat.get("Flow Duration"), None, None, None, None, None, None,
                    label_val, truth, "upload",
                    f"Uploaded file: {file.filename}", batch_id,
                    json.dumps(feat), now,
                ),
            )
            rid = cur.lastrowid
            record_ids.append(rid)
            res = results[i]
            conn.execute(
                """
                INSERT INTO predictions (
                    record_id, batch_id, predicted_category, is_attack, confidence,
                    probabilities, model_version, created_at
                ) VALUES (?,?,?,?,?,?,?,?)
                """,
                (rid, batch_id, res["category"], 1 if res["is_attack"] else 0,
                 res["confidence"], json.dumps(res["probabilities"]),
                 out["model_version"], now),
            )

        # Alerts for predicted attacks only, subject to the confidence threshold.
        threshold = confidence_threshold()
        alert_count = 0
        suppressed = 0
        for i, res in enumerate(results):
            if not res["is_attack"]:
                continue
            if res["confidence"] < threshold:
                suppressed += 1
                continue
            cat = res["category"]
            true_cat = labels[i]
            note = ""
            if true_cat:
                note = f" CSV label column says '{true_cat}'."
            conn.execute(
                """
                INSERT INTO alerts (
                    severity, title, category, source_ip, destination_ip, description,
                    confidence, status, origin, origin_detail, occurred_at, record_id,
                    prediction_id, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    category_severity(cat),
                    f"{category_label(cat)} detected in upload",
                    cat,
                    None, None,
                    (
                        f"Row {i + 1} of '{file.filename}' classified as "
                        f"{category_label(cat)} with {res['confidence'] * 100:.1f}% "
                        f"confidence. Model {out['model_version']}.{note}"
                    ).strip(),
                    res["confidence"],
                    "open",
                    "model",
                    f"Model inference on uploaded file '{file.filename}'",
                    now, record_ids[i], None, now, now,
                ),
            )
            alert_count += 1

        errors: list[str] = []
        if alignment["missing"]:
            errors.append(
                f"{len(alignment['missing'])} model column(s) were absent and were "
                "filled with the training median."
            )
        if rows_with_missing:
            errors.append(
                f"{rows_with_missing} row(s) contained {missing_cells} missing/Infinity "
                "value(s); these were imputed."
            )
        if suppressed:
            errors.append(
                f"{suppressed} attack prediction(s) fell below the {threshold:.2f} "
                "confidence threshold, so no alert was raised for them."
            )
        if truncated:
            errors.append(
                f"Only the first {MAX_UPLOAD_ROWS} rows were processed (file had more)."
            )
        if label_col is None:
            errors.append("No label column found - ground-truth comparison disabled.")

        conn.execute(
            """
            INSERT INTO detection_batches (
                id, filename, total_rows, accepted_rows, rejected_rows, attack_rows,
                model_version, errors, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (
                batch_id, file.filename, total_rows, total_rows, 0, attack_rows,
                out["model_version"], json.dumps(errors), now,
            ),
        )

    preview = [
        {
            "row": r["index"] + 2,  # +2 = header row is line 1
            "category": r["category"],
            "category_label": r["category_label"],
            "is_attack": r["is_attack"],
            "confidence": r["confidence"],
            "truth": labels[r["index"]] if labels[r["index"]] else None,
            "correct": (
                None
                if not labels[r["index"]]
                else to_category(labels[r["index"]]) == r["category"]
            ),
            "top_probabilities": dict(
                sorted(r["probabilities"].items(), key=lambda kv: -kv[1])[:3]
            ),
        }
        for r in results
    ]

    return {
        "batch_id": batch_id,
        "filename": file.filename,
        "model_version": out["model_version"],
        "rows_received": len(df),
        "rows_scored": total_rows,
        "truncated": truncated,
        "attack_rows": attack_rows,
        "benign_rows": total_rows - attack_rows,
        "alerts_created": alert_count,
        "alerts_suppressed": suppressed,
        "confidence_threshold": threshold,
        "predicted_distribution": {
            k: {"count": v, "label": category_label(k)}
            for k, v in sorted(out["summary"].items(), key=lambda kv: -kv[1])
        },
        "missing_values": {
            "missing_cells": missing_cells,
            "rows_with_missing": rows_with_missing,
            "per_column": per_column_missing,
        },
        "alignment": alignment,
        "errors": errors,
        "rows": preview,
        "is_simulated": False,
        "note": f"Predictions produced by the trained model on {file.filename}.",
    }


# ---------------------------------------------------------------------------
# Batch history
# ---------------------------------------------------------------------------
@router.get("/batches")
def list_batches(limit: int = 20) -> dict:
    with get_db() as conn:
        rows = [
            dict(r)
            for r in conn.execute(
                "SELECT * FROM detection_batches ORDER BY created_at DESC LIMIT ?",
                (limit,),
            )
        ]
    for r in rows:
        r["errors"] = loads(r.get("errors"), [])
    return {"items": rows, "total": len(rows)}


@router.get("/batches/{batch_id}")
def batch_detail(batch_id: str, limit: int = 500) -> dict:
    with get_db() as conn:
        meta = conn.execute(
            "SELECT * FROM detection_batches WHERE id = ?", (batch_id,)
        ).fetchone()
        if meta is None:
            raise HTTPException(404, f"Batch {batch_id} not found")
        rows = [
            dict(r)
            for r in conn.execute(
                """
                SELECT p.predicted_category, p.confidence, p.is_attack,
                       p.probabilities, p.created_at,
                       t.id AS record_id, t.label, t.category, t.origin,
                       t.source_ip, t.destination_ip, t.destination_port,
                       t.captured_at
                FROM predictions p
                JOIN traffic_records t ON t.id = p.record_id
                WHERE p.batch_id = ?
                ORDER BY p.id LIMIT ?
                """,
                (batch_id, limit),
            )
        ]

    items = []
    for r in rows:
        r["probabilities"] = loads(r.get("probabilities"), {})
        r["predicted_label"] = category_label(r["predicted_category"])
        r["truth_label"] = category_label(r["category"]) if r["category"] else None
        items.append(r)

    meta = dict(meta)
    meta["errors"] = loads(meta.get("errors"), [])
    return {"batch": meta, "items": items, "returned": len(items)}
