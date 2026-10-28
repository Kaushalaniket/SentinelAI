"""Dashboard endpoints: KPIs, distributions, timeline and recent alerts."""

from __future__ import annotations

from collections import OrderedDict

from fastapi import APIRouter, Query

from ..db import get_db, utcnow
from ..ml.labels import CATEGORY_META, category_label
from ..ml.predictor import model_info_safe
from ..settings import show_simulated

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


@router.get("/summary")
def dashboard_summary(
    days: int = Query(7, ge=1, le=90, description="Days of simulated traffic to plot"),
) -> dict:
    """
    Everything the landing page needs in one request.

    The response always contains a ``data_sources`` block so the UI can state,
    truthfully and permanently, that no live packet capture is running.
    """
    hide_simulated = not show_simulated()
    sim_clause = " WHERE origin <> 'simulated'" if hide_simulated else ""
    alert_clause = " WHERE origin <> 'simulated'" if hide_simulated else ""

    with get_db() as conn:
        total_traffic = conn.execute(
            f"SELECT COUNT(*) AS n FROM traffic_records{sim_clause}"
        ).fetchone()["n"]

        by_origin = {
            r["origin"]: r["n"]
            for r in conn.execute(
                "SELECT origin, COUNT(*) AS n FROM traffic_records GROUP BY origin"
            )
        }

        truth_distribution = OrderedDict(
            (r["category"], r["n"])
            for r in conn.execute(
                "SELECT category, COUNT(*) AS n FROM traffic_records "
                "WHERE origin IN ('dataset','upload') AND category IS NOT NULL "
                "GROUP BY category ORDER BY n DESC"
            )
        )

        predicted_distribution = OrderedDict(
            (r["predicted_category"], r["n"])
            for r in conn.execute(
                "SELECT predicted_category, COUNT(*) AS n FROM predictions "
                "GROUP BY predicted_category ORDER BY n DESC"
            )
        )

        threat_count = conn.execute(
            "SELECT COUNT(*) AS n FROM predictions WHERE is_attack = 1"
        ).fetchone()["n"]

        scored_count = conn.execute("SELECT COUNT(*) AS n FROM predictions").fetchone()["n"]

        severity_counts = {s: 0 for s in ("critical", "high", "medium", "low", "info")}
        for r in conn.execute(
            f"SELECT severity, COUNT(*) AS n FROM alerts{alert_clause} GROUP BY severity"
        ):
            severity_counts[r["severity"]] = r["n"]
        alert_total = sum(severity_counts.values())

        status_counts = {
            r["status"]: r["n"]
            for r in conn.execute(
                f"SELECT status, COUNT(*) AS n FROM alerts{alert_clause} GROUP BY status"
            )
        }

        model_alerts = conn.execute(
            "SELECT COUNT(*) AS n FROM alerts WHERE origin = 'model'"
        ).fetchone()["n"]
        simulated_alerts = conn.execute(
            "SELECT COUNT(*) AS n FROM alerts WHERE origin = 'simulated'"
        ).fetchone()["n"]

        # Hour-of-day histogram (UTC). Works for both the 2017 capture and the
        # synthetic demo rows without mixing incompatible calendar dates.
        hourly = [0] * 24
        for r in conn.execute(
            "SELECT CAST(substr(captured_at, 12, 2) AS INTEGER) AS h, COUNT(*) AS n "
            f"FROM traffic_records{sim_clause} GROUP BY h"
        ):
            h = r["h"]
            if h is not None and 0 <= h <= 23:
                hourly[h] = r["n"]

        top_targets = [
            dict(r)
            for r in conn.execute(
                "SELECT destination_port AS port, COUNT(*) AS n, "
                "SUM(CASE WHEN category <> 'benign' THEN 1 ELSE 0 END) AS attacks "
                "FROM traffic_records"
                + (" WHERE" if not sim_clause else sim_clause + " AND")
                + " destination_port IS NOT NULL "
                "AND destination_port > 0 GROUP BY destination_port "
                "ORDER BY n DESC LIMIT 8"
            )
        ]

        recent_alerts = [
            {
                "id": r["id"],
                "severity": r["severity"],
                "title": r["title"],
                "category": r["category"],
                "category_label": category_label(r["category"] or "benign"),
                "source_ip": r["source_ip"],
                "destination_ip": r["destination_ip"],
                "status": r["status"],
                "origin": r["origin"],
                "confidence": r["confidence"],
                "occurred_at": r["occurred_at"],
                "created_at": r["created_at"],
            }
            for r in conn.execute(
                "SELECT * FROM alerts" + alert_clause
                + " ORDER BY COALESCE(occurred_at, created_at) DESC LIMIT 8"
            )
        ]

        protocol_counts = [
            dict(r)
            for r in conn.execute(
                "SELECT COALESCE(protocol,'UNKNOWN') AS protocol, COUNT(*) AS n "
                f"FROM traffic_records{sim_clause} GROUP BY protocol ORDER BY n DESC LIMIT 6"
            )
        ]

    model = model_info_safe()
    missing_config = []
    if not model.get("available"):
        missing_config.append("model")

    return {
        "generated_at": utcnow(),
        "data_sources": {
            "live_capture": False,
            "headline": "No live packet capture is running",
            "explanation": (
                "SentinelAI is an offline demonstration system. Traffic comes from two "
                "clearly separated places: real labelled flows replayed from the "
                "CIC-IDS2017 research dataset, and synthetic demo records produced by "
                "the built-in generator. Nothing on this page is a live attack."
            ),
            "sources": [
                {
                    "origin": "dataset",
                    "name": "CIC-IDS2017 dataset replay",
                    "count": by_origin.get("dataset", 0),
                    "is_simulated": False,
                    "description": (
                        "Real network flows captured by the Canadian Institute for "
                        "Cybersecurity in July 2017, with their original labels."
                    ),
                },
                {
                    "origin": "simulated",
                    "name": "Simulated demo traffic",
                    "count": by_origin.get("simulated", 0),
                    "is_simulated": True,
                    "description": "Synthetic rows generated by this app for demonstration.",
                },
                {
                    "origin": "upload",
                    "name": "Uploaded CSV records",
                    "count": by_origin.get("upload", 0),
                    "is_simulated": False,
                    "description": "Rows imported from user-uploaded traffic CSV files.",
                },
            ],
        },
        "kpis": {
            "traffic_records": total_traffic,
            "scored_records": scored_count,
            "threats_detected": threat_count,
            "alerts_open": status_counts.get("open", 0),
            "alerts_acknowledged": status_counts.get("acknowledged", 0),
            "alerts_resolved": status_counts.get("resolved", 0),
            "alerts_total": alert_total,
            "alerts_model_generated": model_alerts,
            "alerts_simulated": simulated_alerts,
            "critical_alerts": severity_counts.get("critical", 0),
        },
        "severity_counts": severity_counts,
        "status_counts": status_counts,
        "truth_distribution": {
            k: {"count": v, "label": category_label(k), "severity": CATEGORY_META.get(k, {}).get("severity", "info")}
            for k, v in truth_distribution.items()
        },
        "predicted_distribution": {
            k: {"count": v, "label": category_label(k), "severity": CATEGORY_META.get(k, {}).get("severity", "info")}
            for k, v in predicted_distribution.items()
        },
        "hourly_traffic": hourly,
        "top_target_ports": top_targets,
        "protocol_counts": protocol_counts,
        "recent_alerts": recent_alerts,
        "model": {
            "available": model.get("available", False),
            "model_version": model.get("model_version"),
            "trained_at": model.get("trained_at"),
            "metrics_loaded": "metrics" in model,
        },
        "missing_configuration": missing_config,
        "window_days": days,
        "simulated_visible": not hide_simulated,
    }
