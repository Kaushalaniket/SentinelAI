"""
Runtime inference: load the trained model and score DataFrames.

The API layer only ever talks to :class:`Predictor`; it never touches
scikit-learn directly.  If the model file is missing, every call raises a
clear :class:`ModelNotTrained` error which the routers turn into an honest
HTTP 503 instead of a stack trace.
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd

from .. import config
from .labels import category_label, is_attack
from .preprocess import align_features

_lock = threading.Lock()
_cache: dict[str, Any] | None = None


class ModelNotTrained(Exception):
    """Raised when ``model.joblib`` is absent or unreadable."""


def model_exists() -> bool:
    return Path(config.MODEL_PATH).exists()


def _load() -> dict[str, Any]:
    global _cache
    if _cache is not None:
        return _cache
    if not model_exists():
        raise ModelNotTrained(
            "No trained model found. Train one with:  python -m app.ml.train"
        )
    with _lock:
        if _cache is None:
            try:
                _cache = joblib.load(config.MODEL_PATH)
            except Exception as exc:  # corrupt / wrong sklearn version
                raise ModelNotTrained(f"Could not load the model file: {exc}") from exc
    return _cache


def load_model_info() -> dict[str, Any]:
    """
    Metadata the UI needs *without* scoring anything.

    Returns an ``available: false`` payload when the model has not been
    trained yet so the front-end can show an empty state rather than an error.
    """
    if not model_exists():
        return {
            "available": False,
            "reason": "Model file not found. Train it with `python -m app.ml.train`.",
            "model_path": str(config.MODEL_PATH),
        }
    try:
        payload = _load()
    except ModelNotTrained as exc:
        return {"available": False, "reason": str(exc), "model_path": str(config.MODEL_PATH)}

    return {
        "available": True,
        "model_version": payload.get("model_version"),
        "model_type": payload.get("model_type"),
        "trained_at": payload.get("trained_at"),
        "sklearn_version": payload.get("sklearn_version"),
        "seed": payload.get("seed"),
        "classes": payload.get("classes", []),
        "feature_count": len(payload.get("feature_names", [])),
        "features": payload.get("feature_names", []),
        "file_bytes": Path(config.MODEL_PATH).stat().st_size,
        "file_modified": datetime.fromtimestamp(
            Path(config.MODEL_PATH).stat().st_mtime, tz=timezone.utc
        ).isoformat(timespec="seconds"),
        "metrics_path": str(config.METRICS_PATH),
        "metrics_updated": _metrics_mtime(),
    }


def _metrics_mtime() -> str | None:
    p = Path(config.METRICS_PATH)
    if not p.exists():
        return None
    return datetime.fromtimestamp(p.stat().st_mtime, tz=timezone.utc).isoformat(
        timespec="seconds"
    )


def load_metrics() -> dict[str, Any]:
    """Return the saved evaluation report, or an ``available: false`` payload."""
    p = Path(config.METRICS_PATH)
    if not p.exists():
        return {
            "available": False,
            "reason": "No evaluation report found. Run `python -m app.ml.train`.",
        }
    try:
        doc = json.loads(p.read_text())
    except (OSError, ValueError) as exc:
        return {"available": False, "reason": f"Could not read metrics: {exc}"}
    doc["available"] = True
    doc["metrics_file"] = str(p)
    return doc


def model_info_safe() -> dict[str, Any]:
    """Never-raising variant of :func:`load_model_info` (used by dashboards)."""
    try:
        info = load_model_info()
    except Exception as exc:  # pragma: no cover - defensive
        return {"available": False, "reason": str(exc)}
    if info.get("available"):
        metrics = load_metrics()
        if metrics.get("available"):
            info["metrics"] = {
                "model": metrics.get("model"),
                "baselines": metrics.get("baselines"),
                "provenance": metrics.get("provenance"),
            }
    return info


@dataclass
class RowResult:
    index: int
    category: str
    category_label: str
    is_attack: bool
    confidence: float
    probabilities: dict[str, float]


def predict_frame(df: pd.DataFrame) -> dict[str, Any]:
    """
    Score an arbitrary flow frame.

    Returns a dictionary with:

    ``usable``         whether enough recognised features were present
    ``alignment``      which columns matched / were missing / were ignored
    ``results``        one :class:`RowResult`-like dict per input row
    ``summary``        predicted counts per category
    ``model_version``  version of the model that produced the predictions
    """
    payload = _load()
    features: list[str] = payload["feature_names"]
    pipeline = payload["pipeline"]

    matrix, alignment = align_features(df, features)
    if not alignment["usable"]:
        return {
            "usable": False,
            "alignment": alignment,
            "results": [],
            "summary": {},
            "model_version": payload.get("model_version"),
        }

    proba = pipeline.predict_proba(matrix)
    classes: list[str] = list(pipeline.classes_)
    best_idx = np.argmax(proba, axis=1)

    results: list[dict[str, Any]] = []
    summary: dict[str, int] = {}
    for i in range(len(matrix)):
        cat = str(classes[best_idx[i]])
        confidence = float(proba[i, best_idx[i]])
        probs = {str(c): round(float(p), 6) for c, p in zip(classes, proba[i])}
        results.append(
            {
                "index": int(i),
                "category": cat,
                "category_label": category_label(cat),
                "is_attack": bool(is_attack(cat)),
                "confidence": round(confidence, 6),
                "probabilities": probs,
            }
        )
        summary[cat] = summary.get(cat, 0) + 1

    return {
        "usable": True,
        "alignment": alignment,
        "results": results,
        "summary": summary,
        "model_version": payload.get("model_version"),
    }


def predict_single(row: dict[str, Any]) -> dict[str, Any]:
    """Convenience wrapper: score one record supplied as a plain dict."""
    df = pd.DataFrame([row])
    out = predict_frame(df)
    if not out["usable"]:
        return {"usable": False, "alignment": out["alignment"], "model_version": out["model_version"]}
    return {
        "usable": True,
        "alignment": out["alignment"],
        "prediction": out["results"][0],
        "model_version": out["model_version"],
    }
