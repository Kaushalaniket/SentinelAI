"""
SentinelAI application configuration.

Everything that depends on the filesystem or on environment variables lives
here, so the rest of the code never hard-codes a path.

All paths are resolved relative to the ``backend/`` folder, which means the
project works no matter which directory you start it from.
"""

from __future__ import annotations

import os
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
# backend/app/config.py  ->  parents[1] == backend/
BACKEND_DIR = Path(__file__).resolve().parents[1]
PROJECT_DIR = BACKEND_DIR.parent

DATA_DIR = Path(os.getenv("SENTINELAI_DATA_DIR", BACKEND_DIR / "data"))
RAW_DIR = DATA_DIR / "raw"                      # downloaded CIC-IDS2017 zip
SAMPLE_DIR = DATA_DIR / "samples"               # CSVs the user can upload
ARTIFACT_DIR = DATA_DIR / "artifacts"           # trained model + metrics

DB_PATH = Path(os.getenv("SENTINELAI_DB", DATA_DIR / "sentinelai.db"))
MODEL_PATH = Path(os.getenv("SENTINELAI_MODEL", ARTIFACT_DIR / "model.joblib"))
METRICS_PATH = ARTIFACT_DIR / "metrics.json"
DATASET_CACHE = ARTIFACT_DIR / "train_sample.parquet"
DATASET_ZIP = RAW_DIR / "MachineLearningCSV.zip"

for _p in (DATA_DIR, RAW_DIR, SAMPLE_DIR, ARTIFACT_DIR):
    _p.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------------------
# Dataset provenance (used verbatim in the UI so nothing is mislabelled)
# ---------------------------------------------------------------------------
DATASET_NAME = "CIC-IDS2017"
DATASET_PUBLISHER = "Canadian Institute for Cybersecurity, University of New Brunswick"
DATASET_PAPER = (
    "Sharafaldin, Lashkari & Ghorbani, "
    "'Toward Generating a New Intrusion Detection Dataset and Intrusion "
    "Traffic Characterization', ICISSP 2018"
)
DATASET_OFFICIAL_URL = "https://www.unb.ca/cic/datasets/ids-2017.html"
DATASET_MIRROR_URL = (
    "https://huggingface.co/datasets/bencorn/CICIDS2017/resolve/main/csvs/MachineLearningCSV.zip"
)
DATASET_TOTAL_ROWS = 2_830_743  # full labelled flow count of CIC-IDS2017

# ---------------------------------------------------------------------------
# Model / training defaults
# ---------------------------------------------------------------------------
RANDOM_SEED = int(os.getenv("SENTINELAI_SEED", "42"))
TEST_SIZE = 0.2
N_ESTIMATORS = int(os.getenv("SENTINELAI_N_ESTIMATORS", "128"))
MAX_LEAF_NODES = int(os.getenv("SENTINELAI_MAX_LEAF_NODES", "1024"))

# Per-class row caps used when building the training sample. Rare classes are
# kept in full; very common classes are down-sampled so a laptop can train.
CLASS_CAPS: dict[str, int] = {
    "benign": 90_000,
    "ddos": 60_000,
    "dos": 60_000,
    "portscan": 60_000,
    "bruteforce": 20_000,
    "webattack": 15_000,
    "bot": 5_000,
    "infiltration": 5_000,
    "heartbleed": 5_000,
}

# ---------------------------------------------------------------------------
# Threat intelligence
# ---------------------------------------------------------------------------
# Optional commercial / public API. If the key is absent the application falls
# back to a keyless public blocklist feed instead of failing.
ABUSEIPDB_API_KEY = os.getenv("ABUSEIPDB_API_KEY", "").strip()
VIRUSTOTAL_API_KEY = os.getenv("VIRUSTOTAL_API_KEY", "").strip()

# Keyless, public, regularly updated list of IPs seen in many blocklists.
TI_FEED_URL = os.getenv(
    "SENTINELAI_TI_FEED_URL",
    "https://raw.githubusercontent.com/stamparm/ipsum/master/ipsum.txt",
)
TI_FEED_CACHE = DATA_DIR / "ipsum_cache.txt"
TI_FEED_MAX_AGE_HOURS = int(os.getenv("SENTINELAI_TI_FEED_MAX_AGE", "168"))

# ---------------------------------------------------------------------------
# Server
# ---------------------------------------------------------------------------
API_PREFIX = "/api"
CORS_ORIGINS = [
    o.strip()
    for o in os.getenv(
        "SENTINELAI_CORS",
        "http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000,http://127.0.0.1:3000",
    ).split(",")
    if o.strip()
]
