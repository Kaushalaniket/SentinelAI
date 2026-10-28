"""
Data seeding: real CIC-IDS2017 records, synthetic demo traffic, and inference.

Every row stored by this module records **where it came from**:

===================== ==================================================
``origin``            meaning
===================== ==================================================
``dataset``           a real, labelled flow from CIC-IDS2017. The IP
                      addresses, ports, protocol and timestamp are the
                      genuine values recorded by CIC in July 2017.
``simulated``         produced by the synthetic generator in this file.
                      Nothing about it is a real network event.
``upload``            a row from a CSV the user uploaded.
===================== ==================================================

Simulated rows are never passed to the model and never produce detection
alerts, so the application can never accidentally present a synthetic event as
a machine-learning detection.
"""

from __future__ import annotations

import json
import random
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

import numpy as np
import pandas as pd

from . import config
from .db import get_db, utcnow
from .ml.labels import category_label, category_severity, to_category
from .ml.predictor import ModelNotTrained, model_exists, predict_frame
from .ml.preprocess import feature_columns, find_label_column

# ---------------------------------------------------------------------------
# How much of each category to seed from the real dataset
# ---------------------------------------------------------------------------
SEED_QUOTAS: dict[str, int] = {
    "benign": 200,
    "ddos": 70,
    "dos": 70,
    "portscan": 70,
    "bruteforce": 35,
    "webattack": 30,
    "bot": 25,
    "infiltration": 20,
    "heartbleed": 10,
}

DISPLAY_DTYPES = {
    "Flow ID": "string",
    "Source IP": "string",
    "Destination IP": "string",
    "Timestamp": "string",
    "Label": "string",
}

PROTOCOLS = {1: "ICMP", 6: "TCP", 17: "UDP"}

SIMULATED_ORIGIN_DETAIL = "Synthetic demo generator - not real network traffic"
DATASET_ORIGIN_DETAIL = "CIC-IDS2017 (real labelled capture, July 2017)"


def _confidence_threshold() -> float:
    """Read the runtime setting, defaulting to 0.5 if the DB is not ready yet."""
    from .settings import confidence_threshold

    try:
        return float(confidence_threshold())
    except Exception:
        return 0.5


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _parse_timestamp(raw: object, fallback: datetime) -> str:
    """CIC timestamps look like ``'7/7/2017 8:42'`` (no seconds, no tz)."""
    text = str(raw or "").strip()
    for fmt in ("%d/%m/%Y %H:%M:%S", "%m/%d/%Y %H:%M:%S", "%d/%m/%Y %H:%M", "%m/%d/%Y %H:%M"):
        try:
            return datetime.strptime(text, fmt).replace(tzinfo=timezone.utc).isoformat(
                timespec="seconds"
            )
        except ValueError:
            continue
    return fallback.astimezone(timezone.utc).isoformat(timespec="seconds")


def _int(value: object, default: int = 0) -> int:
    try:
        f = float(value)
        if not np.isfinite(f):
            return default
        return int(f)
    except (TypeError, ValueError):
        return default


def _float(value: object, default: float = 0.0) -> float:
    try:
        f = float(value)
        return f if np.isfinite(f) else default
    except (TypeError, ValueError):
        return default


# ---------------------------------------------------------------------------
# 1. Load real CIC-IDS2017 records
# ---------------------------------------------------------------------------
def dataset_record_count() -> int:
    with get_db() as conn:
        row = conn.execute(
            "SELECT COUNT(*) AS n FROM traffic_records WHERE origin='dataset'"
        ).fetchone()
    return int(row["n"])


def load_dataset_records(
    quotas: dict[str, int] | None = None,
    zip_path=config.DATASET_ZIP,
    seed: int = config.RANDOM_SEED,
    verbose: bool = True,
) -> dict[str, Any]:
    """
    Stream ``GeneratedLabelledFlows.zip`` (the CIC-IDS2017 companion file that
    keeps the original 5-tuple and timestamp) and store a stratified sample.

    Returns a summary dict.  Idempotent: categories that already have enough
    rows in SQLite are skipped.
    """
    from .ml.preprocess import list_zip_csv_members, read_zip_member

    quotas = quotas or SEED_QUOTAS
    rng = random.Random(seed)
    nrng = np.random.default_rng(seed)

    if not zip_path.exists():
        raise FileNotFoundError(
            f"{zip_path} not found. Run: python -m scripts.download_dataset"
        )

    with get_db() as conn:
        existing = {
            r["category"]: r["n"]
            for r in conn.execute(
                "SELECT category, COUNT(*) AS n FROM traffic_records "
                "WHERE origin='dataset' GROUP BY category"
            )
        }
    need = {c: max(0, q - existing.get(c, 0)) for c, q in quotas.items()}
    need = {c: n for c, n in need.items() if n > 0}
    if not need:
        if verbose:
            print("Dataset records already seeded.")
        return {"skipped": True, "inserted": 0, "reason": "quotas already satisfied"}

    members = list_zip_csv_members(zip_path)
    collected: dict[str, list[dict[str, Any]]] = {}
    read_members: list[str] = []

    for member in members:
        if not need:
            break
        df = read_zip_member(zip_path, member)
        label_col = find_label_column(df.columns)
        if label_col is None:
            continue
        read_members.append(member.split("/")[-1])

        cats = df[label_col].map(to_category)
        numeric_feats = feature_columns(df)

        for cat in list(need):
            want = need[cat]
            if want <= 0:
                continue
            idx = np.flatnonzero((cats == cat).to_numpy())
            if idx.size == 0:
                continue
            take = idx if idx.size <= want else np.sort(
                nrng.choice(idx, size=want, replace=False)
            )
            for i in take:
                row = df.iloc[int(i)]
                feats = {
                    f: _float(row[f], float("nan"))
                    for f in numeric_feats
                }
                collected.setdefault(cat, []).append(
                    {
                        "captured_at": _parse_timestamp(
                            row.get("Timestamp"),
                            datetime(2017, 7, 7, tzinfo=timezone.utc),
                        ),
                        "source_ip": str(row.get("Source IP") or "").strip(),
                        "destination_ip": str(row.get("Destination IP") or "").strip(),
                        "source_port": _int(row.get("Source Port")),
                        "destination_port": _int(row.get("Destination Port")),
                        "protocol": PROTOCOLS.get(
                            _int(row.get("Protocol")), str(row.get("Protocol") or "")
                        ),
                        "flow_duration_ms": _float(row.get("Flow Duration")) / 1000.0,
                        "total_packets": _int(row.get("Total Fwd Packets"))
                        + _int(row.get("Total Backward Packets")),
                        "total_bytes": _int(row.get("Total Length of Fwd Packets"))
                        + _int(row.get("Total Length of Bwd Packets")),
                        "fwd_bytes": _int(row.get("Total Length of Fwd Packets")),
                        "bwd_bytes": _int(row.get("Total Length of Bwd Packets")),
                        "bytes_per_sec": _float(row.get("Flow Bytes/s")),
                        "packets_per_sec": _float(row.get("Flow Packets/s")),
                        "label": str(row.get(label_col) or "").strip(),
                        "category": cat,
                        "origin": "dataset",
                        "origin_detail": DATASET_ORIGIN_DETAIL,
                        "features": feats,
                    }
                )
            need[cat] = max(0, need[cat] - len(take))

        del df

    rows: list[dict[str, Any]] = []
    for cat, items in collected.items():
        rng.shuffle(items)
        rows.extend(items)

    now = utcnow()
    with get_db() as conn:
        conn.executemany(
            """
            INSERT INTO traffic_records (
                captured_at, source_ip, destination_ip, source_port, destination_port,
                protocol, flow_duration_ms, total_packets, total_bytes, fwd_bytes,
                bwd_bytes, packets_per_sec, bytes_per_sec, label, category, origin,
                origin_detail, features, created_at
            ) VALUES (
                :captured_at, :source_ip, :destination_ip, :source_port, :destination_port,
                :protocol, :flow_duration_ms, :total_packets, :total_bytes, :fwd_bytes,
                :bwd_bytes, :packets_per_sec, :bytes_per_sec, :label, :category, :origin,
                :origin_detail, :features_json, :created_at
            )
            """,
            [
                {**r, "features_json": json.dumps(r["features"]), "created_at": now}
                for r in rows
            ],
        )

    summary = {
        "inserted": len(rows),
        "per_category": {c: len(v) for c, v in collected.items()},
        "files_read": read_members,
        "source": config.DATASET_NAME,
        "note": "Real labelled flows from CIC-IDS2017 (real IPs, ports and timestamps).",
    }
    if verbose:
        print(f"Seeded {len(rows)} real dataset records: {summary['per_category']}")
    return summary


# ---------------------------------------------------------------------------
# 2. Synthetic demo traffic
# ---------------------------------------------------------------------------
_BENIGN_DESTS = [
    ("93.184.216.34", 443, "TCP"), ("142.250.196.110", 443, "TCP"),
    ("151.101.1.69", 80, "TCP"), ("104.16.132.229", 443, "TCP"),
    ("192.0.2.15", 53, "UDP"), ("198.51.100.7", 123, "UDP"),
    ("203.0.113.50", 22, "TCP"), ("8.8.8.8", 53, "UDP"),
]
_ATTACK_PROFILES: list[tuple[str, str, int, str, str]] = [
    # category, attacker ip, target port, protocol, description keyword
    ("ddos", "185.220.101.44", 80, "TCP", "volumetric HTTP flood"),
    ("dos", "185.220.101.5", 443, "TCP", "slow-rate connection exhaustion"),
    ("portscan", "45.155.205.233", 0, "TCP", "sequential port probing"),
    ("webattack", "198.18.0.99", 443, "TCP", "SQL injection / XSS attempts"),
    ("bruteforce", "198.18.4.21", 22, "TCP", "credential spraying"),
    ("bot", "192.0.2.201", 4444, "TCP", "command-and-control beacon"),
    ("infiltration", "10.0.0.66", 445, "TCP", "internal lateral movement"),
    ("heartbleed", "198.18.9.12", 443, "TCP", "CVE-2014-0160 memory probe"),
]


def generate_simulated_traffic(
    count: int = 50,
    attack_ratio: float = 0.35,
    seed: int | None = None,
    verbose: bool = True,
) -> dict[str, Any]:
    """Create synthetic flow rows.  Always stored with ``origin='simulated'``."""
    rng = random.Random(seed if seed is not None else config.RANDOM_SEED + count)
    now = datetime.now(timezone.utc)
    rows: list[dict[str, Any]] = []

    for i in range(count):
        is_bad = rng.random() < attack_ratio
        if is_bad:
            cat, src, dport, proto, _kw = rng.choice(_ATTACK_PROFILES)
            if cat == "portscan":
                dport = rng.choice([21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 3389, 8080])
            dst = f"10.10.{rng.randint(0, 3)}.{rng.randint(2, 250)}"
            sport = rng.randint(32768, 60999)
            fwd, bwd = rng.randint(200, 9000), rng.randint(0, 900)
            dur = rng.uniform(0.4, 40.0)
        else:
            cat = "benign"
            dst, dport, proto = rng.choice(_BENIGN_DESTS)
            src = f"10.10.{rng.randint(0, 3)}.{rng.randint(2, 250)}"
            sport = rng.randint(32768, 60999)
            fwd, bwd = rng.randint(60, 2400), rng.randint(60, 24000)
            dur = rng.uniform(0.01, 3.0)

        total = fwd + bwd
        packets = max(2, int(total / max(64, rng.randint(64, 1460))) + 2)
        captured = (now - timedelta(seconds=rng.randint(5, 60 * 60 * 36))).isoformat(
            timespec="seconds"
        )
        rows.append(
            {
                "captured_at": captured,
                "source_ip": src,
                "destination_ip": dst,
                "source_port": sport,
                "destination_port": dport,
                "protocol": proto,
                "flow_duration_ms": round(dur * 1000, 2),
                "total_packets": packets,
                "total_bytes": total,
                "fwd_bytes": fwd,
                "bwd_bytes": bwd,
                "packets_per_sec": round(packets / dur, 2) if dur > 0 else 0.0,
                "bytes_per_sec": round(total / dur, 2) if dur > 0 else 0.0,
                "label": None,
                "category": cat,
                "origin": "simulated",
                "origin_detail": SIMULATED_ORIGIN_DETAIL,
                "features_json": None,
            }
        )

    now_str = utcnow()
    with get_db() as conn:
        conn.executemany(
            """
            INSERT INTO traffic_records (
                captured_at, source_ip, destination_ip, source_port, destination_port,
                protocol, flow_duration_ms, total_packets, total_bytes, fwd_bytes,
                bwd_bytes, packets_per_sec, bytes_per_sec, label, category, origin,
                origin_detail, features, created_at
            ) VALUES (
                :captured_at, :source_ip, :destination_ip, :source_port, :destination_port,
                :protocol, :flow_duration_ms, :total_packets, :total_bytes, :fwd_bytes,
                :bwd_bytes, :packets_per_sec, :bytes_per_sec, :label, :category, :origin,
                :origin_detail, :features_json, :created_at
            )
            """,
            [{**r, "created_at": now_str} for r in rows],
        )
        n = conn.execute("SELECT COUNT(*) AS n FROM traffic_records").fetchone()["n"]

    summary = {
        "inserted": len(rows),
        "attack_rows": sum(1 for r in rows if r["category"] != "benign"),
        "total_traffic_rows": n,
        "origin": "simulated",
        "note": SIMULATED_ORIGIN_DETAIL,
    }
    if verbose:
        print(f"Generated {len(rows)} simulated flows ({summary['attack_rows']} attack-labelled)")
    return summary


# ---------------------------------------------------------------------------
# 3. Run the trained model over stored records
# ---------------------------------------------------------------------------
def score_records(
    record_ids: list[int] | None = None,
    only_without_prediction: bool = True,
    create_alerts: bool = True,
    batch_id: str | None = None,
    verbose: bool = True,
) -> dict[str, Any]:
    """
    Run the trained model over traffic records that carry a stored feature
    vector (dataset rows and uploaded rows) and persist the predictions.

    Alerts are created only for predicted attack traffic, and they carry
    ``origin='model'`` so the UI can distinguish them from simulated ones.
    """
    if not model_exists():
        raise ModelNotTrained("No trained model found. Run: python -m app.ml.train")

    batch_id = batch_id or uuid.uuid4().hex[:16]
    with get_db() as conn:
        sql = (
            "SELECT id, source_ip, destination_ip, source_port, destination_port, "
            "protocol, category AS true_category, origin, origin_detail, captured_at, features "
            "FROM traffic_records WHERE features IS NOT NULL"
        )
        params: list[Any] = []
        if record_ids:
            sql += f" AND id IN ({','.join('?' * len(record_ids))})"
            params.extend(record_ids)
        if only_without_prediction:
            sql += " AND id NOT IN (SELECT record_id FROM predictions WHERE record_id IS NOT NULL)"
        sql += " ORDER BY id"
        rows = [dict(r) for r in conn.execute(sql, params)]

    if not rows:
        return {"scored": 0, "alerts_created": 0, "batch_id": batch_id, "skipped": True}

    features = pd.DataFrame([json.loads(r["features"]) for r in rows])
    out = predict_frame(features)
    if not out["usable"]:
        return {
            "scored": 0,
            "alerts_created": 0,
            "batch_id": batch_id,
            "error": "Feature alignment failed",
            "alignment": out["alignment"],
        }

    now = utcnow()
    alert_rows: list[dict[str, Any]] = []
    threshold = _confidence_threshold()
    suppressed = 0
    with get_db() as conn:
        for row, res in zip(rows, out["results"]):
            cur = conn.execute(
                """
                INSERT INTO predictions (
                    record_id, batch_id, predicted_category, is_attack, confidence,
                    probabilities, model_version, created_at
                ) VALUES (?,?,?,?,?,?,?,?)
                """,
                (
                    row["id"],
                    batch_id,
                    res["category"],
                    1 if res["is_attack"] else 0,
                    res["confidence"],
                    json.dumps(res["probabilities"]),
                    out["model_version"],
                    now,
                ),
            )
            pred_id = cur.lastrowid
            if create_alerts and res["is_attack"]:
                if res["confidence"] < threshold:
                    suppressed += 1
                    continue
                cat = res["category"]
                true_cat = row["true_category"]
                match_note = (
                    "Model prediction matches the dataset label."
                    if true_cat == cat
                    else (
                        f"Dataset label for this flow is '{category_label(true_cat)}'."
                        if true_cat
                        else ""
                    )
                )
                port = row["destination_port"]
                alert_rows.append(
                    {
                        "severity": category_severity(cat),
                        "title": f"{category_label(cat)} traffic detected",
                        "category": cat,
                        "source_ip": row["source_ip"],
                        "destination_ip": row["destination_ip"],
                        "description": (
                            f"Scored by the trained model ({out['model_version']}) with "
                            f"{res['confidence'] * 100:.1f}% confidence against "
                            f"{row['source_ip']}:{row['source_port']} -> "
                            f"{row['destination_ip']}:{port} ({row['protocol'] or 'n/a'}). "
                            f"{match_note}"
                        ).strip(),
                        "confidence": res["confidence"],
                        "status": "open",
                        "origin": "model",
                        "origin_detail": (
                            "Generated by the trained model from "
                            f"{row['origin_detail'] or row['origin']} records"
                        ),
                        "occurred_at": row["captured_at"],
                        "record_id": row["id"],
                        "prediction_id": pred_id,
                        "created_at": now,
                        "updated_at": now,
                    }
                )

        conn.executemany(
            """
            INSERT INTO alerts (
                severity, title, category, source_ip, destination_ip, description,
                confidence, status, origin, origin_detail, occurred_at, record_id,
                prediction_id, created_at, updated_at
            ) VALUES (
                :severity, :title, :category, :source_ip, :destination_ip, :description,
                :confidence, :status, :origin, :origin_detail, :occurred_at, :record_id,
                :prediction_id, :created_at, :updated_at
            )
            """,
            alert_rows,
        )

    if verbose:
        print(
            f"Scored {len(rows)} records -> {sum(1 for r in out['results'] if r['is_attack'])} "
            f"predicted attacks, {len(alert_rows)} alerts created"
        )
    return {
        "scored": len(rows),
        "predicted_attacks": sum(1 for r in out["results"] if r["is_attack"]),
        "alerts_created": len(alert_rows),
        "alerts_suppressed_below_threshold": suppressed,
        "confidence_threshold": threshold,
        "batch_id": batch_id,
        "model_version": out["model_version"],
        "summary": out["summary"],
    }


# ---------------------------------------------------------------------------
# 4. Explicitly labelled simulated alerts (demo history)
# ---------------------------------------------------------------------------
_SIMULATED_ALERT_TEMPLATES = [
    ("high", "Repeated SSH login failures", "bruteforce"),
    ("medium", "Port sweep from single host", "portscan"),
    ("critical", "Outbound C2 beacon pattern", "bot"),
    ("low", "Unusual DNS query volume", "benign"),
    ("high", "SQL injection attempt blocked", "webattack"),
    ("critical", "Volumetric HTTP flood", "ddos"),
    ("medium", "SMB enumeration activity", "infiltration"),
    ("info", "Policy baseline deviation", "benign"),
]


def seed_simulated_alerts(count: int = 16, verbose: bool = True) -> dict[str, Any]:
    """Insert demo alerts that are unmistakably marked as simulated."""
    rng = random.Random(7)
    now = datetime.now(timezone.utc)
    rows = []
    for i in range(count):
        severity, title, cat = _SIMULATED_ALERT_TEMPLATES[i % len(_SIMULATED_ALERT_TEMPLATES)]
        occurred = (now - timedelta(minutes=rng.randint(10, 60 * 48))).isoformat(
            timespec="seconds"
        )
        ts = utcnow()
        rows.append(
            {
                "severity": severity,
                "title": title,
                "category": cat,
                "source_ip": f"198.18.{rng.randint(0, 255)}.{rng.randint(1, 254)}",
                "destination_ip": f"10.10.{rng.randint(0, 3)}.{rng.randint(2, 250)}",
                "description": (
                    "SIMULATED DEMO ALERT - generated by the sample-data seeder to "
                    "populate the alert console. This is not a real detection and does "
                    "not come from packet capture or from the model."
                ),
                "confidence": None,
                "status": rng.choice(["open", "open", "acknowledged", "resolved"]),
                "origin": "simulated",
                "origin_detail": "Sample data seeder (demo only)",
                "occurred_at": occurred,
                "record_id": None,
                "prediction_id": None,
                "created_at": ts,
                "updated_at": ts,
            }
        )
    with get_db() as conn:
        conn.executemany(
            """
            INSERT INTO alerts (
                severity, title, category, source_ip, destination_ip, description,
                confidence, status, origin, origin_detail, occurred_at, record_id,
                prediction_id, created_at, updated_at
            ) VALUES (
                :severity, :title, :category, :source_ip, :destination_ip, :description,
                :confidence, :status, :origin, :origin_detail, :occurred_at, :record_id,
                :prediction_id, :created_at, :updated_at
            )
            """,
            rows,
        )
    if verbose:
        print(f"Seeded {count} clearly-labelled simulated demo alerts")
    return {"inserted": len(rows), "origin": "simulated"}


# ---------------------------------------------------------------------------
# 5. One-shot bootstrap used on first API start
# ---------------------------------------------------------------------------
def bootstrap(seed_simulated: bool = True, load_dataset: bool = True, verbose: bool = False):
    """
    Populate an empty database so the dashboard is not blank on first run.

    Every section is skipped if its data already exists, so calling this on
    every startup costs a single indexed COUNT query.
    """
    from .db import init_db

    init_db()
    report: dict[str, Any] = {}

    with get_db() as conn:
        simulated_rows = conn.execute(
            "SELECT COUNT(*) AS n FROM traffic_records WHERE origin='simulated'"
        ).fetchone()["n"]
        dataset_rows = dataset_record_count()
        simulated_alerts = conn.execute(
            "SELECT COUNT(*) AS n FROM alerts WHERE origin='simulated'"
        ).fetchone()["n"]

    if simulated_rows == 0 and seed_simulated:
        report["simulated_traffic"] = generate_simulated_traffic(
            count=250, attack_ratio=0.35, verbose=verbose
        )

    if dataset_rows == 0 and load_dataset:
        try:
            report["dataset_records"] = load_dataset_records(verbose=verbose)
        except FileNotFoundError as exc:
            report["dataset_records"] = {"error": str(exc)}
        except Exception as exc:  # pragma: no cover - surfaced in /api/health
            report["dataset_records"] = {"error": str(exc)}

    if simulated_alerts == 0 and seed_simulated:
        report["simulated_alerts"] = seed_simulated_alerts(verbose=verbose)

    # Run the model over any dataset/upload rows that have not been scored yet.
    if model_exists():
        try:
            report["inference"] = score_records(verbose=verbose, only_without_prediction=True)
        except Exception as exc:  # pragma: no cover
            report["inference"] = {"error": str(exc)}
    else:
        report["inference"] = {"error": "model not trained yet"}

    return report
