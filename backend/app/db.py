"""
SQLite persistence layer.

The whole application shares one small SQLite file.  A fresh, empty database
is created automatically the first time the API starts, and the schema is
created with ``CREATE TABLE IF NOT EXISTS`` so starting the server twice is
always safe.

Design notes for beginners
--------------------------
* ``sqlite3`` ships with Python - no extra service to install.
* We use the ``sqlite3.Row`` row factory so rows behave like dictionaries.
* Every connection is short lived (one per request).  SQLite is fast enough
  for a demonstration project and this keeps the code simple.
"""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from . import config

SCHEMA = """
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS traffic_records (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    captured_at        TEXT    NOT NULL,
    source_ip          TEXT,
    destination_ip     TEXT,
    source_port        INTEGER,
    destination_port   INTEGER,
    protocol           TEXT,
    flow_duration_ms   REAL,
    total_packets      INTEGER,
    total_bytes        INTEGER,
    fwd_bytes          INTEGER,
    bwd_bytes          INTEGER,
    packets_per_sec    REAL,
    bytes_per_sec      REAL,
    label              TEXT,          -- ground truth when known, NULL otherwise
    category           TEXT,          -- benign / ddos / dos / portscan / ...
    origin             TEXT    NOT NULL,  -- 'dataset' | 'simulated' | 'upload'
    origin_detail      TEXT,          -- e.g. 'CIC-IDS2017 Friday-Afternoon-DDoS'
    batch_id           TEXT,
    features           TEXT,          -- JSON: full CICFlowMeter feature vector
    created_at         TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_traffic_created  ON traffic_records(created_at);
CREATE INDEX IF NOT EXISTS idx_traffic_origin   ON traffic_records(origin);
CREATE INDEX IF NOT EXISTS idx_traffic_category ON traffic_records(category);

CREATE TABLE IF NOT EXISTS predictions (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id         INTEGER REFERENCES traffic_records(id) ON DELETE CASCADE,
    batch_id          TEXT,
    predicted_category TEXT   NOT NULL,
    is_attack         INTEGER NOT NULL,
    confidence        REAL,
    probabilities     TEXT,           -- JSON object {category: probability}
    model_version     TEXT,
    created_at        TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pred_record ON predictions(record_id);
CREATE INDEX IF NOT EXISTS idx_pred_batch  ON predictions(batch_id);

CREATE TABLE IF NOT EXISTS alerts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    severity        TEXT    NOT NULL,          -- critical | high | medium | low | info
    title           TEXT    NOT NULL,
    category        TEXT,
    source_ip       TEXT,
    destination_ip  TEXT,
    description     TEXT,
    confidence      REAL,
    status          TEXT    NOT NULL DEFAULT 'open',   -- open | acknowledged | resolved
    origin          TEXT    NOT NULL,                  -- 'model' | 'simulated'
    origin_detail   TEXT,
    occurred_at     TEXT,          -- time of the flow itself (dataset timestamp)
    record_id       INTEGER REFERENCES traffic_records(id) ON DELETE SET NULL,
    prediction_id   INTEGER REFERENCES predictions(id) ON DELETE SET NULL,
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_alerts_created  ON alerts(created_at);
CREATE INDEX IF NOT EXISTS idx_alerts_severity ON alerts(severity);
CREATE INDEX IF NOT EXISTS idx_alerts_status   ON alerts(status);

CREATE TABLE IF NOT EXISTS detection_batches (
    id             TEXT PRIMARY KEY,
    filename       TEXT,
    total_rows     INTEGER,
    accepted_rows  INTEGER,
    rejected_rows  INTEGER,
    attack_rows    INTEGER,
    model_version  TEXT,
    errors         TEXT,           -- JSON list
    created_at     TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS intel_lookups (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ip          TEXT    NOT NULL,
    verdict     TEXT    NOT NULL,   -- malicious | suspicious | clean | unknown
    score       INTEGER,
    source      TEXT    NOT NULL,   -- 'AbuseIPDB' | 'VirusTotal' | 'IPsum blocklist'
    is_live     INTEGER NOT NULL,   -- 1 = called a remote API, 0 = offline/cached
    categories  TEXT,
    detail      TEXT,
    error       TEXT,
    queried_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_intel_ip ON intel_lookups(ip);

CREATE TABLE IF NOT EXISTS app_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


def utcnow() -> str:
    """ISO-8601 timestamp in UTC - used for every ``*_at`` column."""
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def connect(db_path: Path | str | None = None) -> sqlite3.Connection:
    """Open a connection with sane defaults for a small web app."""
    path = Path(db_path or config.DB_PATH)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


@contextmanager
def get_db(db_path: Path | str | None = None) -> Iterator[sqlite3.Connection]:
    """Context manager that commits on success and always closes the handle."""
    conn = connect(db_path)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db(db_path: Path | str | None = None) -> None:
    """Create the schema if it does not exist yet (idempotent)."""
    with get_db(db_path) as conn:
        conn.executescript(SCHEMA)


def row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    return dict(row) if row is not None else None


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [dict(r) for r in rows]


def loads(value: str | None, default: Any = None) -> Any:
    if value is None:
        return default
    try:
        return json.loads(value)
    except (TypeError, ValueError):
        return default
