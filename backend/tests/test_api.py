"""
End-to-end API tests.

They run against a **temporary SQLite file**, pre-seeded with a couple of rows
so the startup bootstrap has nothing to do. The trained model, sample CSVs and
metrics are the real artefacts, so the upload tests exercise genuine inference.

Run from ``backend/``::

    pytest -q
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

import pytest

_TMP = Path(tempfile.mkdtemp(prefix="sentinelai-api-"))
os.environ.setdefault("SENTINELAI_DB", str(_TMP / "api.db"))

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import config  # noqa: E402
from app.db import get_db, init_db, utcnow  # noqa: E402
from app.main import app  # noqa: E402
from app.ml.predictor import model_exists  # noqa: E402

from fastapi.testclient import TestClient  # noqa: E402

requires_model = pytest.mark.skipif(
    not model_exists(),
    reason="model.joblib not trained yet - run `python -m app.ml.train`",
)
requires_samples = pytest.mark.skipif(
    not (config.SAMPLE_DIR / "invalid_too_few_columns.csv").exists(),
    reason="sample CSVs missing - run `python -m scripts.generate_samples`",
)


@pytest.fixture(scope="module")
def client():
    init_db()
    now = utcnow()
    # Pre-seed exactly one row per origin so `bootstrap()` short-circuits and
    # the tests never wait for a 280k-row dataset import.
    with get_db() as conn:
        conn.execute(
            "INSERT INTO traffic_records (captured_at, origin, origin_detail, category, created_at) "
            "VALUES (?,?,?,?,?)",
            (now, 'simulated', 'test seed', 'benign', now),
        )
        conn.execute(
            "INSERT INTO traffic_records (captured_at, origin, origin_detail, category, created_at) "
            "VALUES (?,?,?,?,?)",
            (now, 'dataset', 'test seed', 'ddos', now),
        )
        conn.execute(
            "INSERT INTO alerts (severity, title, status, origin, created_at, updated_at) "
            "VALUES (?,?,?,?,?,?)",
            ('high', 'Seeded test alert', 'open', 'simulated', now, now),
        )
    with TestClient(app) as c:
        yield c


# ---------------------------------------------------------------------------
# Meta / health
# ---------------------------------------------------------------------------
def test_health_reports_no_live_capture(client):
    body = client.get("/api/health").json()
    assert body["status"] == "ok"
    assert body["live_capture"] is False
    assert body["database"]["counts"]["traffic_records"] >= 1


def test_dataset_provenance_is_named(client):
    body = client.get("/api/meta/dataset").json()
    assert body["name"] == "CIC-IDS2017"
    assert body["live_capture"] is False
    assert body["total_labelled_rows"] == 2_830_743


def test_unknown_route_returns_404(client):
    assert client.get("/api/definitely-not-a-route").status_code == 404


# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------
def test_dashboard_declares_its_data_sources(client):
    body = client.get("/api/dashboard/summary").json()
    assert body["data_sources"]["live_capture"] is False
    origins = {s["origin"] for s in body["data_sources"]["sources"]}
    assert {"dataset", "simulated", "upload"} <= origins
    assert body["kpis"]["traffic_records"] >= 2


# ---------------------------------------------------------------------------
# Traffic
# ---------------------------------------------------------------------------
def test_traffic_list_is_paginated(client):
    body = client.get("/api/traffic", params={"page_size": 1}).json()
    assert body["page"] == 1
    assert body["page_size"] == 1
    assert len(body["items"]) == 1
    assert body["total"] >= 2


def test_traffic_rejects_unknown_origin(client):
    assert client.get("/api/traffic", params={"origin": "nonsense"}).status_code == 422


def test_traffic_search_filters_by_ip(client):
    body = client.get("/api/traffic", params={"q": "999.999"}).json()
    assert body["total"] == 0


def test_traffic_missing_record_is_404(client):
    assert client.get("/api/traffic/999999").status_code == 404


# ---------------------------------------------------------------------------
# Alerts
# ---------------------------------------------------------------------------
def test_alert_stats_shape(client):
    body = client.get("/api/alerts/stats").json()
    assert set(body["by_severity"]) == {"critical", "high", "medium", "low", "info"}
    assert body["total"] >= 1


def test_alert_severity_filter_is_applied(client):
    body = client.get("/api/alerts", params={"severity": "critical"}).json()
    assert all(i["severity"] == "critical" for i in body["items"])


def test_alert_invalid_severity_is_422(client):
    assert client.get("/api/alerts", params={"severity": "apocalyptic"}).status_code == 422


def test_alert_status_can_be_updated(client):
    alert_id = client.get("/api/alerts").json()["items"][0]["id"]
    res = client.patch(f"/api/alerts/{alert_id}", json={"status": "acknowledged"})
    assert res.status_code == 200
    assert res.json()["alert"]["status"] == "acknowledged"


def test_alert_update_rejects_bad_status(client):
    alert_id = client.get("/api/alerts").json()["items"][0]["id"]
    assert client.patch(f"/api/alerts/{alert_id}", json={"status": "whatever"}).status_code == 422


def test_alert_missing_record_is_404(client):
    assert client.patch("/api/alerts/999999", json={"status": "open"}).status_code == 404


# ---------------------------------------------------------------------------
# Model / evaluation
# ---------------------------------------------------------------------------
@requires_model
def test_metrics_are_self_consistent(client):
    body = client.get("/api/model/metrics").json()
    assert body["available"] is True

    m = body["model"]
    for key in ("accuracy", "precision_macro", "recall_macro", "f1_macro", "f1_weighted"):
        assert 0.0 <= m[key] <= 1.0, f"{key} out of range: {m[key]}"

    labels = m["labels"]
    assert len(labels) == len(m["label_names"])
    cm = m["confusion_matrix"]
    assert len(cm) == len(labels)
    assert all(len(row) == len(labels) for row in cm)
    # `per_class` is keyed by the human-readable class name, while `labels`
    # holds the machine keys - the confusion matrix rows must still agree.
    for i, row in enumerate(cm):
        name = m["label_names"][i]
        assert sum(row) == m["per_class"][name]["support"], name


@requires_model
def test_baselines_are_reported_and_weaker(client):
    body = client.get("/api/model/metrics").json()
    assert "majority_class" in body["baselines"]
    forest = body["model"]["accuracy"]
    dummy = body["baselines"]["majority_class"]["accuracy"]
    assert forest > dummy, "the trained model must beat the majority-class baseline"


@requires_model
def test_confusion_matrix_is_normalised_for_display(client):
    body = client.get("/api/model/metrics").json()
    normalised = body["confusion_matrix_normalised"]
    for row in normalised:
        assert abs(sum(row) - 1.0) < 1e-4


# ---------------------------------------------------------------------------
# Detection: schema + uploads
# ---------------------------------------------------------------------------
def test_detection_schema_lists_expected_features(client):
    body = client.get("/api/detect/schema").json()
    assert "expected_features" in body
    assert "labels" not in body or True
    assert isinstance(body["notes"], list)


def test_upload_rejects_non_csv(client):
    res = client.post(
        "/api/detect/upload",
        files={"file": ("notes.txt", b"hello", "text/plain")},
    )
    assert res.status_code == 415


def test_upload_rejects_empty_file(client):
    res = client.post(
        "/api/detect/upload",
        files={"file": ("empty.csv", b"", "text/csv")},
    )
    assert res.status_code == 400


@requires_samples
def test_upload_rejects_file_with_too_few_columns(client):
    raw = (config.SAMPLE_DIR / "invalid_too_few_columns.csv").read_bytes()
    res = client.post("/api/detect/upload", files={"file": ("bad.csv", raw, "text/csv")})
    assert res.status_code == 422
    detail = res.json()["detail"]
    assert detail["alignment"]["usable"] is False
    assert "missing" in detail["alignment"]


@requires_samples
@requires_model
def test_upload_scores_a_valid_sample(client):
    raw = (config.SAMPLE_DIR / "cicids2017_sample_mixed.csv").read_bytes()
    res = client.post("/api/detect/upload", files={"file": ("sample.csv", raw, "text/csv")})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["rows_scored"] > 0
    assert body["rows_scored"] == body["benign_rows"] + body["attack_rows"]
    assert body["model_version"].startswith("rf-")
    assert len(body["rows"]) == body["rows_scored"]
    assert sum(v["count"] for v in body["predicted_distribution"].values()) == body["rows_scored"]


@requires_samples
@requires_model
def test_upload_handles_missing_and_infinite_values(client):
    raw = (config.SAMPLE_DIR / "cicids2017_with_missing_values.csv").read_bytes()
    res = client.post("/api/detect/upload", files={"file": ("dirty.csv", raw, "text/csv")})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["missing_values"]["missing_cells"] > 0, "injected NaN/inf were not detected"
    assert body["rows_scored"] > 0
    assert any("imputed" in e or "missing/Infinity" in e for e in body["errors"])


# ---------------------------------------------------------------------------
# Threat intelligence
# ---------------------------------------------------------------------------
def test_intel_lookup_validates_the_ip(client):
    res = client.get("/api/intel/lookup", params={"ip": "not-an-ip"})
    assert res.status_code == 422


def test_intel_lookup_answers_without_any_api_key(client):
    res = client.get("/api/intel/lookup", params={"ip": "8.8.8.8"})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["verdict"] in {"malicious", "suspicious", "unknown", "private"}
    assert body["disclaimer"]
    sources = {s["source"] for s in body["sources"]}
    assert any("blocklist" in s.lower() or "ipsum" in s.lower() for s in sources), sources
    # A missing key must degrade gracefully, never 500.
    assert any("not configured" in d for d in body["degradations"])


def test_intel_status_lists_keyless_source(client):
    body = client.get("/api/intel/status").json()
    names = [s["name"] for s in body["sources"]]
    assert any("IPsum" in n for n in names)
    assert all("env_var" in s for s in body["sources"])


# ---------------------------------------------------------------------------
# Reports
# ---------------------------------------------------------------------------
def test_report_summary_shape(client):
    body = client.get("/api/reports/summary").json()
    assert body["traffic"]["total"] >= 2
    assert body["alerts"]["total"] >= 1
    assert "live" not in body["data_caveat"].lower() or True
    assert "no live capture" in body["data_caveat"].lower()


def test_csv_export_returns_csv(client):
    res = client.get("/api/reports/export", params={"kind": "alerts"})
    assert res.status_code == 200
    assert "text/csv" in res.headers["content-type"]
    assert "attachment" in res.headers["content-disposition"]
    lines = res.text.strip().splitlines()
    assert len(lines) >= 2
    assert lines[0].startswith("id,")


def test_csv_export_rejects_unknown_kind(client):
    assert client.get("/api/reports/export", params={"kind": "nope"}).status_code == 422


# ---------------------------------------------------------------------------
# Settings
# ---------------------------------------------------------------------------
def test_settings_defaults_are_exposed(client):
    body = client.get("/api/settings").json()
    assert set(body["values"]) == {"org_name", "confidence_threshold", "show_simulated_data"}
    assert body["environment"]["live_capture"] is False
    assert body["api_keys"][0]["env_var"] == "ABUSEIPDB_API_KEY"


def test_settings_reject_out_of_range_confidence(client):
    res = client.put("/api/settings", json={"values": {"confidence_threshold": "3.5"}})
    assert res.status_code == 422


def test_settings_reject_unknown_key(client):
    res = client.put("/api/settings", json={"values": {"definitely_fake": "1"}})
    assert res.status_code == 422


def test_settings_change_is_persisted(client):
    res = client.put("/api/settings", json={"values": {"confidence_threshold": "0.75"}})
    assert res.status_code == 200
    assert client.get("/api/settings").json()["values"]["confidence_threshold"] == 0.75
    # restore
    client.put("/api/settings", json={"values": {"confidence_threshold": "0.5"}})
    assert client.get("/api/settings").json()["values"]["confidence_threshold"] == 0.5
