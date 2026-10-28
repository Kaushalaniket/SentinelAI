"""
Unit tests for the machine-learning layer.

These do not touch the HTTP layer. They check the things that silently break
in intrusion-detection projects: label encoding, feature alignment when a
column is missing, and infinite values leaking into the model matrix.

Run from ``backend/``::

    pytest -q
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

# Configure a throw-away database BEFORE importing the app so tests never
# disturb the real one.
_TMP = Path(tempfile.mkdtemp(prefix="sentinelai-test-"))
# Only the database is redirected - samples, model and metrics stay real so the
# tests exercise the artefacts the application actually ships with.
os.environ.setdefault("SENTINELAI_DB", str(_TMP / "test.db"))

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import config  # noqa: E402
from app.ml import labels as L  # noqa: E402
from app.ml.preprocess import align_features, clean_numeric, strip_columns  # noqa: E402
from app.ml.predictor import load_model_info, model_exists, predict_frame  # noqa: E402

requires_model = pytest.mark.skipif(
    not model_exists(),
    reason="model.joblib not trained yet - run `python -m app.ml.train`",
)


class TestLabelMapping:
    def test_every_raw_cic_label_maps_to_a_known_category(self):
        raw = [
            "BENIGN", "DDoS", "DoS Hulk", "DoS GoldenEye", "DoS Slowhttptest",
            "DoS slowloris", "PortScan", "FTP-Patator", "SSH-Patator", "Bot",
            "Infiltration", "Heartbleed",
        ]
        expected = [
            "benign", "ddos", "dos", "dos", "dos", "dos", "portscan",
            "bruteforce", "bruteforce", "bot", "infiltration", "heartbleed",
        ]
        assert [L.to_category(r) for r in raw] == expected

    def test_mangled_web_attack_glyph_still_maps_correctly(self):
        # The distributed CSV stores "Web Attack \ufffd Brute Force".
        for variant in (
            "Web Attack \ufffd Brute Force",
            "Web Attack - Brute Force",
            "Web Attack – XSS",
            "web attack sql injection",
        ):
            assert L.to_category(variant) == "webattack", variant

    def test_canonical_label_is_idempotent(self):
        assert L.canonical_label(L.canonical_label("  DoS   Hulk  ")) == "dos hulk"

    def test_unknown_labels_are_kept_visible_not_dropped(self):
        assert L.to_category("Some Brand New Attack") == "somebrandnewattack"

    def test_every_attack_category_has_metadata(self):
        for cat in L.ATTACK_CATEGORIES:
            assert cat in L.CATEGORY_META, cat
            assert L.CATEGORY_META[cat]["severity"] in L.SEVERITY_ORDER
            assert L.category_color(cat).startswith("#")

    def test_benign_is_not_an_attack(self):
        assert not L.is_attack("benign")
        assert L.is_attack("ddos")


class TestPreprocessing:
    def test_strip_columns_trims_and_deduplicates(self):
        df = pd.DataFrame([[1, 2, 3]], columns=[" A ", "A", "B"])
        out = strip_columns(df)
        assert list(out.columns) == ["A", "A__2", "B"]

    def test_clean_numeric_converts_infinities_and_non_numeric(self):
        df = pd.DataFrame({"a": ["1", "Infinity", "oops"], "b": ["NaN", "2.5", None]})
        out = clean_numeric(df)
        values = out.to_numpy(dtype=float)
        assert np.isnan(values[1, 0]), "+inf must become NaN for the imputer"
        assert np.isnan(values[2, 0]), "unparseable text must become NaN"
        assert values[1, 1] == 2.5

    def test_align_features_reports_missing_and_unexpected(self):
        expected = ["Destination Port", "Flow Duration", "Bwd Packet Length Std"]
        upload = pd.DataFrame(
            {"Destination Port": [80], "Some Vendor Column": [7]}
        )
        matrix, report = align_features(upload, expected)

        assert report["missing"] == ["Flow Duration", "Bwd Packet Length Std"]
        assert report["unexpected"] == ["Some Vendor Column"]
        assert list(matrix.columns) == expected
        assert report["matched_count"] == 1

    def test_alignment_is_rejected_when_too_few_features_match(self):
        expected = [f"feature_{i}" for i in range(70)]
        upload = pd.DataFrame({"feature_0": [1], "feature_1": [2]})
        _, report = align_features(upload, expected)
        assert report["usable"] is False
        assert report["required_min_features"] == 35

    def test_alignment_is_accepted_for_a_real_cic_header(self):
        expected = load_model_info().get("features") or []
        if not expected:
            pytest.skip("model not trained - no feature list to compare against")
        header = pd.read_csv(
            config.SAMPLE_DIR / "cicids2017_sample_mixed.csv", nrows=1
        )
        _, report = align_features(strip_columns(header), expected)
        assert report["usable"] is True
        assert report["matched_count"] == len(expected)


@requires_model
class TestModel:
    def test_model_metadata_is_complete(self):
        info = load_model_info()
        assert info["available"] is True
        assert info["feature_count"] >= 50
        assert len(info["classes"]) >= 3
        assert info["model_version"].startswith("rf-")

    def test_predictions_are_probabilities_that_sum_to_one(self):
        # Supply every model feature (zeros stand in for "not observed"),
        # because a record with only two columns is deliberately rejected.
        features = load_model_info()["features"]
        rows = []
        for port, duration in ((80, 1000), (445, 500000)):
            row = dict.fromkeys(features, 0.0)
            row["Destination Port"] = port
            row["Flow Duration"] = duration
            rows.append(row)
        out = predict_frame(pd.DataFrame(rows))
        assert out["usable"] is True
        assert len(out["results"]) == 2
        for row in out["results"]:
            total = sum(row["probabilities"].values())
            assert 0.999 <= total <= 1.001
            assert 0.0 <= row["confidence"] <= 1.0
            assert row["category"] in row["probabilities"]

    def test_real_ddos_sample_is_classified_as_an_attack(self):
        sample = pd.read_csv(config.SAMPLE_DIR / "cicids2017_sample_mixed.csv")
        ddos_rows = sample[sample["Label"].str.strip().str.lower() == "ddos"].head(10)
        if ddos_rows.empty:
            pytest.skip("no DDoS rows in the sample file")
        out = predict_frame(ddos_rows)
        assert out["usable"] is True
        # 10 out of 10 is not guaranteed, but the model must be substantially right.
        hits = sum(1 for r in out["results"] if r["is_attack"])
        assert hits >= 8, f"only {hits}/10 known DDoS rows flagged as attacks"
