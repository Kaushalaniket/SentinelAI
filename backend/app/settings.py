"""
Application settings stored in SQLite.

Settings are plain string key/value pairs.  ``DEFAULTS`` documents each one,
its coercion rule and whether it is purely cosmetic - so the Settings page can
be generated from the same source of truth the backend actually uses.

Only three settings exist, and all three genuinely change backend behaviour:
they are read on every request, not merely stored for display.
"""

from __future__ import annotations

import json
import threading
from typing import Any

from .db import get_db, utcnow

DEFAULTS: dict[str, dict[str, Any]] = {
    "org_name": {
        "default": "SentinelAI SOC",
        "type": "string",
        "label": "Organisation / console name",
        "description": "Display name shown in the sidebar and page titles.",
        "affects": "ui",
        "max_length": 60,
    },
    "confidence_threshold": {
        "default": "0.5",
        "type": "float",
        "label": "Alert confidence threshold",
        "description": (
            "Predictions with confidence below this value are still recorded, "
            "but no alert is raised for them."
        ),
        "affects": "alert creation",
        "min": 0.0,
        "max": 1.0,
    },
    "show_simulated_data": {
        "default": "true",
        "type": "bool",
        "label": "Show simulated demo data",
        "description": (
            "When disabled, rows and alerts with origin='simulated' are hidden "
            "everywhere. Dataset and uploaded records are unaffected."
        ),
        "affects": "traffic, alerts and dashboard queries",
    },
}

_lock = threading.Lock()


def _coerce(key: str, raw: str) -> Any:
    spec = DEFAULTS[key]
    if spec["type"] == "float":
        try:
            value = float(raw)
        except (TypeError, ValueError):
            return float(spec["default"])
        return max(spec["min"], min(spec["max"], value))
    if spec["type"] == "bool":
        return str(raw).strip().lower() in {"1", "true", "yes", "on"}
    return str(raw)[: spec.get("max_length", 2000)]


def get_settings() -> dict[str, Any]:
    """Return the effective settings plus their metadata."""
    with get_db() as conn:
        stored = {r["key"]: r["value"] for r in conn.execute("SELECT key, value FROM app_settings")}

    out: dict[str, Any] = {"values": {}, "meta": {}}
    for key, spec in DEFAULTS.items():
        raw = stored.get(key, str(spec["default"]))
        out["values"][key] = _coerce(key, raw)
        out["meta"][key] = {
            "label": spec["label"],
            "description": spec["description"],
            "type": spec["type"],
            "affects": spec["affects"],
            "default": spec["default"],
            "is_default": raw == str(spec["default"]),
            **(
                {"min": spec["min"], "max": spec["max"]}
                if spec["type"] == "float"
                else {}
            ),
        }
    return out


def get(key: str) -> Any:
    """Effective value of a single setting (coerced, with default fallback)."""
    if key not in DEFAULTS:
        raise KeyError(key)
    with get_db() as conn:
        row = conn.execute("SELECT value FROM app_settings WHERE key = ?", (key,)).fetchone()
    raw = row["value"] if row else str(DEFAULTS[key]["default"])
    return _coerce(key, raw)


def confidence_threshold() -> float:
    return float(get("confidence_threshold"))


def show_simulated() -> bool:
    return bool(get("show_simulated_data"))


def update(values: dict[str, str]) -> dict[str, Any]:
    """Validate and persist a batch of settings."""
    now = utcnow()
    unknown = [k for k in values if k not in DEFAULTS]
    if unknown:
        raise ValueError(f"Unknown setting(s): {', '.join(sorted(unknown))}")

    with _lock, get_db() as conn:
        for key, raw in values.items():
            spec = DEFAULTS[key]
            if spec["type"] == "float":
                try:
                    number = float(raw)
                except (TypeError, ValueError) as exc:
                    raise ValueError(f"{key} must be a number between "
                                     f"{spec['min']} and {spec['max']}") from exc
                if not spec["min"] <= number <= spec["max"]:
                    raise ValueError(
                        f"{key} must be between {spec['min']} and {spec['max']}"
                    )
            conn.execute(
                "INSERT INTO app_settings (key, value, updated_at) VALUES (?,?,?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, "
                "updated_at = excluded.updated_at",
                (key, str(raw), now),
            )
    return get_settings()


def reset() -> dict[str, Any]:
    """Restore every setting to its documented default."""
    with _lock, get_db() as conn:
        conn.execute("DELETE FROM app_settings")
    return get_settings()


def export_json() -> str:
    return json.dumps(get_settings(), indent=2)
