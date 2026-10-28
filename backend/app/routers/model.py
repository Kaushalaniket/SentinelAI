"""Model endpoints: metadata, evaluation metrics, confusion matrix, importance."""

from __future__ import annotations

from fastapi import APIRouter

from .. import config
from ..ml.labels import category_severity
from ..ml.predictor import load_metrics, model_info_safe

router = APIRouter(prefix="/model", tags=["model"])


@router.get("")
def model_info() -> dict:
    """Model metadata plus the full held-out evaluation report."""
    info = model_info_safe()
    metrics = load_metrics()
    return {
        "model": info,
        "metrics": metrics,
        "training_command": "python -m app.ml.train",
        "dataset_command": "python -m app.ml.dataset",
        "artifacts": {
            "model_path": str(config.MODEL_PATH),
            "metrics_path": str(config.METRICS_PATH),
            "dataset_cache": str(config.DATASET_CACHE),
        },
    }


@router.get("/metrics")
def model_metrics() -> dict:
    """Only the evaluation report - used by the AI Detection page charts."""
    metrics = load_metrics()
    if metrics.get("available"):
        model_block = metrics.get("model", {})
        metrics["confusion_matrix_normalised"] = _normalise(
            model_block.get("confusion_matrix", [])
        )
        metrics["class_rows"] = [
            {
                "key": key,
                "label": name,
                "severity": category_severity(key),
                **vals,
            }
            for key, name, vals in _iter_classes(model_block)
        ]
    return metrics


def _iter_classes(model_block: dict):
    labels = model_block.get("labels", [])
    names = model_block.get("label_names", [])
    per_class = model_block.get("per_class", {})
    for key, name in zip(labels, names):
        yield key, name, per_class.get(name, {})


def _normalise(cm: list[list[list[int]]]) -> list[list[float]]:
    """Row-normalise the confusion matrix (each true class sums to 1.0)."""
    out = []
    for row in cm:
        total = sum(row) or 1
        out.append([round(v / total, 6) for v in row])
    return out


@router.get("/features")
def model_features() -> dict:
    """Feature list with the impurity-based importances from the forest."""
    metrics = load_metrics()
    if not metrics.get("available"):
        return {"available": False, "features": [], "importances": []}
    return {
        "available": True,
        "features": metrics.get("feature_importances", []),
        "feature_count": metrics.get("feature_count"),
        "note": (
            "Importances are the mean decrease in Gini impurity reported by "
            "scikit-learn's RandomForestClassifier (in-sample, not permutation-based)."
        ),
    }
