"""Settings endpoints: read, validate and persist runtime configuration."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .. import config as app_config
from .. import settings as settings_store
from ..db import get_db
from ..ml.predictor import model_info_safe
from ..schemas import SettingsUpdateRequest

router = APIRouter(prefix="/settings", tags=["settings"])


@router.get("")
def read_settings() -> dict:
    data = settings_store.get_settings()
    data["model"] = {
        "available": model_info_safe().get("available", False),
        "model_version": model_info_safe().get("model_version"),
    }
    data["environment"] = {
        "database_path": str(app_config.DB_PATH),
        "model_path": str(app_config.MODEL_PATH),
        "dataset_zip": str(app_config.DATASET_ZIP),
        "dataset_present": app_config.DATASET_ZIP.exists(),
        "dataset_name": app_config.DATASET_NAME,
        "dataset_url": app_config.DATASET_OFFICIAL_URL,
        "python": __import__("platform").python_version(),
        "live_capture": False,
    }
    data["api_keys"] = [
        {
            "env_var": "ABUSEIPDB_API_KEY",
            "configured": bool(app_config.ABUSEIPDB_API_KEY),
            "provider": "AbuseIPDB",
            "used_by": "Threat Intelligence live lookups",
        },
        {
            "env_var": "VIRUSTOTAL_API_KEY",
            "configured": bool(app_config.VIRUSTOTAL_API_KEY),
            "provider": "VirusTotal",
            "used_by": "Threat Intelligence live lookups",
        },
    ]
    return data


@router.put("")
def update_settings(body: SettingsUpdateRequest) -> dict:
    if not body.values:
        raise HTTPException(422, "Provide at least one setting to update.")
    try:
        return settings_store.update(body.values)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc


@router.post("/reset")
def reset_settings() -> dict:
    return settings_store.reset()


@router.get("/diagnostics")
def diagnostics() -> dict:
    """Read-only health information for the Troubleshooting card."""
    with get_db() as conn:
        counts = {
            table: conn.execute(f"SELECT COUNT(*) AS n FROM {table}").fetchone()["n"]
            for table in (
                "traffic_records",
                "predictions",
                "alerts",
                "detection_batches",
                "intel_lookups",
            )
        }
    model = model_info_safe()
    return {
        "tables": counts,
        "model_available": model.get("available", False),
        "model_reason": model.get("reason"),
        "dataset_present": app_config.DATASET_ZIP.exists(),
        "status": "ok" if model.get("available") else "model_missing",
    }
