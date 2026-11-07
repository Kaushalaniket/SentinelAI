"""Parity gate for the Android export.

The Android detector runs model.onnx through ONNX Runtime Mobile. It must agree
with the scikit-learn pipeline on the bundled labelled sample, otherwise the
on-device detections would not be the trained model's detections.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import pytest

warnings = pytest.importorskip("warnings")
warnings.filterwarnings("ignore")

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

from app.ml import preprocess as pp  # noqa: E402

ARTIFACTS = BACKEND / "data" / "artifacts"
MODEL_PATH = ARTIFACTS / "model.joblib"
ONNX_PATH = ARTIFACTS / "model.onnx"
CONTRACT_PATH = ARTIFACTS / "model_contract.json"
SAMPLE_PATH = BACKEND / "data" / "samples" / "cicids2017_sample_mixed.csv"

MAX_DELTA = 1e-3


@pytest.fixture(scope="module")
def artefact() -> dict:
    return joblib.load(MODEL_PATH)


@pytest.fixture(scope="module")
def exported() -> bytes:
    if not ONNX_PATH.exists() or not CONTRACT_PATH.exists():
        pytest.skip("model.onnx not exported yet - run scripts/export_onnx.py")
    return ONNX_PATH.read_bytes()


def _matrix(feature_names: list[str]) -> np.ndarray:
    raw = pp.strip_columns(pd.read_csv(SAMPLE_PATH))
    frame = pp.clean_numeric(raw.drop(columns=["Label"]))
    matrix, _meta = pp.align_features(frame, feature_names)
    return matrix.astype(np.float32).values


def test_contract_matches_artefact(artefact: dict, exported: bytes) -> None:
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    assert contract["feature_names"] == list(artefact["feature_names"])
    assert contract["classes"] == [str(c) for c in artefact["classes"]]
    assert len(contract["feature_names"]) == 70
    assert len(contract["classes"]) == 9
    assert contract["opset"] == 17


def test_contract_checksum_matches_file(exported: bytes) -> None:
    import hashlib

    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    assert hashlib.sha256(exported).hexdigest() == contract["onnx_sha256"]


def test_onnx_matches_sklearn(artefact: dict, exported: bytes) -> None:
    ort = pytest.importorskip("onnxruntime")
    pipeline = artefact["pipeline"]
    matrix = _matrix(list(artefact["feature_names"]))

    session = ort.InferenceSession(exported, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    labels, probs = session.run(None, {input_name: matrix})

    sk_labels = pipeline.predict(matrix.astype(np.float64))
    sk_probs = pipeline.predict_proba(matrix.astype(np.float64))

    agreement = int((np.asarray(labels).ravel() == sk_labels).sum())
    delta = float(np.abs(np.asarray(probs) - sk_probs).max())

    print(f"agreement {agreement}/{len(sk_labels)} delta {delta:.3e}")
    assert agreement == len(sk_labels), f"only {agreement}/{len(sk_labels)} agree"
    assert delta < MAX_DELTA, f"delta {delta:.3e} exceeds {MAX_DELTA}"


def test_missing_approximated_features_still_predicts(artefact: dict) -> None:
    """A tunnel cannot compute 12 CIC columns exactly; NaN must stay predictable."""
    pipeline = artefact["pipeline"]
    feature_names = list(artefact["feature_names"])
    matrix = _matrix(feature_names)
    frame = pd.DataFrame(matrix, columns=feature_names)
    approximations = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))["approximated_features"]
    for column in approximations:
        frame[column] = np.nan
    predictions = pipeline.predict(frame.values.astype(np.float64))
    assert len(predictions) == len(matrix)
    assert all(isinstance(p, str) for p in predictions)
