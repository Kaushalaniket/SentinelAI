"""Export the trained SentinelAI pipeline to ONNX and write the model contract.

Run from backend/:  .venv/bin/python scripts/export_onnx.py

The exported graph covers the whole pipeline (SimpleImputer -> StandardScaler ->
RandomForestClassifier) so the Android app cannot drift from the Python model by
re-implementing preprocessing. Gate: argmax agreement 140/140 and max probability
delta < 1e-3 on the bundled labelled sample (float32 vs float64 arithmetic).
"""
from __future__ import annotations

import hashlib
import json
import sys
import warnings
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

warnings.filterwarnings("ignore")

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

from app.ml import preprocess as pp  # noqa: E402

ARTIFACTS = BACKEND / "data" / "artifacts"
MODEL_PATH = ARTIFACTS / "model.joblib"
ONNX_PATH = ARTIFACTS / "model.onnx"
CONTRACT_PATH = ARTIFACTS / "model_contract.json"
SAMPLE_PATH = BACKEND / "data" / "samples" / "cicids2017_sample_mixed.csv"
SAMPLE_EXPECTED_PATH = ARTIFACTS / "sample_expected.json"

OPSET = 17
LABEL_COLUMN = "Label"
MAX_DELTA = 1e-3


def load_artefact() -> dict:
    artefact = joblib.load(MODEL_PATH)
    if not isinstance(artefact, dict) or "pipeline" not in artefact:
        raise SystemExit(f"unexpected artefact structure in {MODEL_PATH}")
    return artefact


def sample_matrix(feature_names: list[str]) -> tuple[np.ndarray, pd.Series]:
    raw = pp.strip_columns(pd.read_csv(SAMPLE_PATH))
    labels = raw[LABEL_COLUMN].astype(str)
    frame = pp.clean_numeric(raw.drop(columns=[LABEL_COLUMN]))
    matrix, _meta = pp.align_features(frame, feature_names)
    return matrix.astype(np.float32).values, labels


def convert(artefact: dict) -> bytes:
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType

    pipeline = artefact["pipeline"]
    n_features = len(artefact["feature_names"])
    model = convert_sklearn(
        pipeline,
        "sentinelai",
        initial_types=[("input", FloatTensorType([None, n_features]))],
        target_opset=OPSET,
        options={id(pipeline): {"zipmap": False}},
    )
    return model.SerializeToString()


def verify(artefact: dict, onnx_bytes: bytes) -> dict:
    import onnxruntime as ort

    feature_names = list(artefact["feature_names"])
    pipeline = artefact["pipeline"]
    matrix, _labels = sample_matrix(feature_names)

    session = ort.InferenceSession(onnx_bytes, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: matrix})
    onnx_labels, onnx_probs = outputs[0], np.asarray(outputs[1])

    sk_labels = pipeline.predict(matrix.astype(np.float64))
    sk_probs = pipeline.predict_proba(matrix.astype(np.float64))

    agreement = int((np.asarray(onnx_labels).ravel() == sk_labels).sum())
    total = int(len(sk_labels))
    max_delta = float(np.abs(onnx_probs - sk_probs).max())

    print(f"rows                 : {total}")
    print(f"argmax agreement     : {agreement}/{total}")
    print(f"max probability delta: {max_delta:.3e}")

    if agreement != total:
        raise SystemExit(f"parity failure: only {agreement}/{total} predictions agree")
    if max_delta >= MAX_DELTA:
        raise SystemExit(f"parity failure: delta {max_delta:.3e} exceeds {MAX_DELTA:g}")
    return {"rows": total, "agreement": agreement, "max_probability_delta": max_delta}


def contract(artefact: dict, onnx_bytes: bytes, parity: dict) -> dict:
    return {
        "model_version": artefact.get("model_version"),
        "model_type": artefact.get("model_type"),
        "trained_at": artefact.get("trained_at"),
        "sklearn_version": artefact.get("sklearn_version"),
        "opset": OPSET,
        "input_name": "input",
        "input_shape": ["batch", len(artefact["feature_names"])],
        "input_dtype": "float32",
        "output_names": ["output_label", "output_probability"],
        "feature_names": list(artefact["feature_names"]),
        "classes": [str(c) for c in artefact["classes"]],
        "default_threshold": 0.5,
        "onnx_bytes": len(onnx_bytes),
        "onnx_sha256": hashlib.sha256(onnx_bytes).hexdigest(),
        "parity": parity,
        "approximated_features": [
            "Subflow Fwd Packets",
            "Subflow Fwd Bytes",
            "Subflow Bwd Packets",
            "Subflow Bwd Bytes",
            "Active Mean",
            "Active Std",
            "Active Max",
            "Active Min",
            "Idle Mean",
            "Idle Std",
            "Idle Max",
            "Idle Min",
        ],
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "notes": [
            "Graph covers imputer + scaler + forest; preprocessing must not be re-implemented on device.",
            "Missing values must be passed as NaN so the fitted median imputer handles them.",
            "Parity is float32 versus float64 and is gated at 1e-3, not 1e-5.",
            "Metrics in metrics.json are held-out CIC-IDS2017 results, never device accuracy.",
        ],
    }


def expected_labels(artefact: dict) -> dict:
    """Reference labels the Android self-test compares its ONNX output against."""
    feature_names = list(artefact["feature_names"])
    matrix, labels = sample_matrix(feature_names)
    predictions = artefact["pipeline"].predict(matrix.astype(np.float64))
    return {
        "model_version": artefact.get("model_version"),
        "count": int(len(predictions)),
        "reference": "scikit-learn pipeline on the bundled 140-row sample",
        "predicted_labels": [str(value) for value in predictions],
        "true_labels": [str(value) for value in labels],
    }


def main() -> None:
    artefact = load_artefact()
    onnx_bytes = convert(artefact)
    ONNX_PATH.write_bytes(onnx_bytes)
    parity = verify(artefact, onnx_bytes)
    doc = contract(artefact, onnx_bytes, parity)
    CONTRACT_PATH.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    SAMPLE_EXPECTED_PATH.write_text(
        json.dumps(expected_labels(artefact), indent=2) + "\n", encoding="utf-8"
    )
    print(f"wrote {SAMPLE_EXPECTED_PATH}")
    print(f"wrote {ONNX_PATH} ({len(onnx_bytes):,} bytes)")
    print(f"wrote {CONTRACT_PATH}")
    print(f"sha256 {doc['onnx_sha256']}")


if __name__ == "__main__":
    main()
