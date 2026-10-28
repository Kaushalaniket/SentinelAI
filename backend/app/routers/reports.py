"""Reports: summary statistics and CSV export."""

from __future__ import annotations

import csv
import io
from datetime import datetime, timezone
from typing import Any, Literal

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse

from ..db import get_db
from ..ml.labels import SEVERITY_ORDER, category_label
from ..ml.predictor import load_metrics

router = APIRouter(prefix="/reports", tags=["reports"])

EXPORT_KINDS = ("traffic", "alerts", "predictions", "intel")


def _timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")


# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
@router.get("/summary")
def report_summary() -> dict:
    """Every aggregate the Reports page renders, in one call."""
    with get_db() as conn:
        traffic_total = conn.execute(
            "SELECT COUNT(*) AS n FROM traffic_records"
        ).fetchone()["n"]
        traffic_by_origin = {
            r["origin"]: r["n"]
            for r in conn.execute(
                "SELECT origin, COUNT(*) AS n FROM traffic_records GROUP BY origin"
            )
        }
        traffic_by_category = {
            r["category"]: r["n"]
            for r in conn.execute(
                "SELECT category, COUNT(*) AS n FROM traffic_records "
                "GROUP BY category ORDER BY n DESC"
            )
        }
        traffic_by_protocol = {
            r["protocol"]: r["n"]
            for r in conn.execute(
                "SELECT COALESCE(protocol,'UNKNOWN') AS protocol, COUNT(*) AS n "
                "FROM traffic_records GROUP BY protocol ORDER BY n DESC"
            )
        }

        bytes_stats = dict(
            conn.execute(
                "SELECT COALESCE(SUM(total_bytes),0) AS total_bytes, "
                "COALESCE(AVG(total_bytes),0) AS avg_bytes, "
                "COALESCE(SUM(total_packets),0) AS total_packets, "
                "COALESCE(AVG(flow_duration_ms),0) AS avg_duration_ms "
                "FROM traffic_records"
            ).fetchone()
        )

        alerts_total = conn.execute("SELECT COUNT(*) AS n FROM alerts").fetchone()["n"]
        alerts_by_severity = {s: 0 for s in SEVERITY_ORDER}
        for r in conn.execute("SELECT severity, COUNT(*) AS n FROM alerts GROUP BY severity"):
            alerts_by_severity[r["severity"]] = r["n"]
        alerts_by_origin = {
            r["origin"]: r["n"]
            for r in conn.execute("SELECT origin, COUNT(*) AS n FROM alerts GROUP BY origin")
        }
        alerts_by_status = {
            r["status"]: r["n"]
            for r in conn.execute("SELECT status, COUNT(*) AS n FROM alerts GROUP BY status")
        }

        pred_total = conn.execute("SELECT COUNT(*) AS n FROM predictions").fetchone()["n"]
        pred_attacks = conn.execute(
            "SELECT COUNT(*) AS n FROM predictions WHERE is_attack = 1"
        ).fetchone()["n"]
        pred_by_category = {
            r["predicted_category"]: r["n"]
            for r in conn.execute(
                "SELECT predicted_category, COUNT(*) AS n FROM predictions "
                "GROUP BY predicted_category ORDER BY n DESC"
            )
        }
        confusion_vs_truth = [
            dict(r)
            for r in conn.execute(
                """
                SELECT t.category AS actual, p.predicted_category AS predicted,
                       COUNT(*) AS n
                FROM predictions p JOIN traffic_records t ON t.id = p.record_id
                WHERE t.category IS NOT NULL
                GROUP BY t.category, p.predicted_category
                ORDER BY n DESC
                """
            )
        ]

        top_sources = [
            dict(r)
            for r in conn.execute(
                "SELECT source_ip AS ip, COUNT(*) AS flows, "
                "SUM(CASE WHEN category <> 'benign' THEN 1 ELSE 0 END) AS attacks "
                "FROM traffic_records WHERE source_ip IS NOT NULL AND source_ip <> '' "
                "GROUP BY source_ip ORDER BY attacks DESC, flows DESC LIMIT 10"
            )
        ]

        batch_count = conn.execute(
            "SELECT COUNT(*) AS n FROM detection_batches"
        ).fetchone()["n"]
        intel_count = conn.execute("SELECT COUNT(*) AS n FROM intel_lookups").fetchone()["n"]

    metrics = load_metrics()
    model_block = metrics.get("model", {}) if metrics.get("available") else {}

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "traffic": {
            "total": traffic_total,
            "by_origin": traffic_by_origin,
            "by_category": {
                k: {"count": v, "label": category_label(k)} for k, v in traffic_by_category.items()
            },
            "by_protocol": traffic_by_protocol,
            "volume": {k: round(float(v or 0), 2) for k, v in bytes_stats.items()},
        },
        "alerts": {
            "total": alerts_total,
            "by_severity": alerts_by_severity,
            "by_origin": alerts_by_origin,
            "by_status": alerts_by_status,
        },
        "predictions": {
            "total": pred_total,
            "attacks": pred_attacks,
            "benign": pred_total - pred_attacks,
            "by_category": pred_by_category,
            "confusion_vs_ground_truth": confusion_vs_truth,
            "batches": batch_count,
        },
        "threat_intel": {"lookups": intel_count},
        "model": {
            "available": metrics.get("available", False),
            "model_version": metrics.get("model_version"),
            "model_type": metrics.get("model_type"),
            "trained_at": metrics.get("trained_at"),
            "accuracy": model_block.get("accuracy"),
            "precision_macro": model_block.get("precision_macro"),
            "recall_macro": model_block.get("recall_macro"),
            "f1_macro": model_block.get("f1_macro"),
            "f1_weighted": model_block.get("f1_weighted"),
            "test_rows": model_block.get("per_class") and sum(
                v["support"] for v in model_block["per_class"].values()
            ),
            "baselines": {
                k: {"accuracy": v.get("accuracy"), "f1_macro": v.get("f1_macro")}
                for k, v in (metrics.get("baselines") or {}).items()
            },
        },
        "top_sources": top_sources,
        "export_kinds": list(EXPORT_KINDS),
        "data_caveat": (
            "All figures describe this SQLite database only. Traffic comes from the "
            "CIC-IDS2017 dataset replay and from the simulated demo generator - "
            "there is no live capture."
        ),
    }


# ---------------------------------------------------------------------------
# CSV export
# ---------------------------------------------------------------------------
def _stream_csv(header: list[str], rows) -> StreamingResponse:
    def generate():
        buf = io.StringIO()
        writer = csv.writer(buf, lineterminator="\n")
        writer.writerow(header)
        yield buf.getvalue()
        buf.seek(0)
        buf.truncate(0)
        for chunk in rows:
            writer.writerow(chunk)
            yield buf.getvalue()
            buf.seek(0)
            buf.truncate(0)

    filename = f"sentinelai-{_timestamp()}.csv"
    return StreamingResponse(
        generate(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/export")
def export_csv(
    kind: Literal["traffic", "alerts", "predictions", "intel"] = Query("traffic"),
    limit: int = Query(50_000, ge=1, le=200_000),
    origin: str | None = None,
) -> StreamingResponse:
    """Stream one of the four datasets as CSV."""
    if kind not in EXPORT_KINDS:
        raise HTTPException(422, f"kind must be one of {EXPORT_KINDS}")

    with get_db() as conn:
        if kind == "traffic":
            clause = " WHERE origin = ?" if origin else ""
            params: list[Any] = [origin] if origin else []
            rows = conn.execute(
                f"SELECT id, captured_at, origin, origin_detail, source_ip, destination_ip, "
                f"source_port, destination_port, protocol, flow_duration_ms, total_packets, "
                f"total_bytes, fwd_bytes, bwd_bytes, packets_per_sec, bytes_per_sec, "
                f"label, category, batch_id FROM traffic_records{clause} "
                f"ORDER BY id LIMIT ?",
                [*params, limit],
            )
            header = [
                "id", "captured_at", "origin", "origin_detail", "source_ip", "destination_ip",
                "source_port", "destination_port", "protocol", "flow_duration_ms",
                "total_packets", "total_bytes", "fwd_bytes", "bwd_bytes",
                "packets_per_sec", "bytes_per_sec", "label", "category", "batch_id",
            ]
            body = ([r[c] for c in header] for r in rows)

        elif kind == "alerts":
            rows = conn.execute(
                "SELECT id, created_at, occurred_at, severity, status, origin, origin_detail, "
                "title, category, source_ip, destination_ip, confidence, description "
                "FROM alerts ORDER BY id LIMIT ?",
                (limit,),
            )
            header = [
                "id", "created_at", "occurred_at", "severity", "status", "origin",
                "origin_detail", "title", "category", "source_ip", "destination_ip",
                "confidence", "description",
            ]
            body = ([r[c] for c in header] for r in rows)

        elif kind == "predictions":
            rows = conn.execute(
                "SELECT p.id, p.created_at, p.batch_id, p.model_version, p.record_id, "
                "p.predicted_category, p.is_attack, p.confidence, t.origin, t.label, "
                "t.category AS ground_truth "
                "FROM predictions p LEFT JOIN traffic_records t ON t.id = p.record_id "
                "ORDER BY p.id LIMIT ?",
                (limit,),
            )
            header = [
                "id", "created_at", "batch_id", "model_version", "record_id",
                "predicted_category", "is_attack", "confidence", "record_origin",
                "label", "ground_truth",
            ]
            body = ([r[c] for c in header] for r in rows)

        else:  # intel
            rows = conn.execute(
                "SELECT id, queried_at, ip, verdict, score, source, is_live, categories, "
                "detail, error FROM intel_lookups ORDER BY id LIMIT ?",
                (limit,),
            )
            header = [
                "id", "queried_at", "ip", "verdict", "score", "source", "is_live",
                "categories", "detail", "error",
            ]
            body = ([r[c] for c in header] for r in rows)

        return _stream_csv(header, list(body))
