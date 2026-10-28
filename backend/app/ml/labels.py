"""
Attack category taxonomy.

CIC-IDS2017 ships 15 raw labels (``BENIGN`` plus 14 attack labels).  Some of
those labels describe the *same family* of attack (four separate DoS variants,
three separate web-attack variants, ...), so SentinelAI groups them into a
smaller set of **attack categories** that a SOC analyst would actually triage.

This grouping is applied consistently everywhere: during training, when
scoring an uploaded CSV, and when rendering charts in the UI.

The mapping is deliberately explicit - nothing here invents an attack class
that is not present in the source dataset.
"""

from __future__ import annotations

import re

# ---------------------------------------------------------------------------
# Category definitions
# ---------------------------------------------------------------------------
# ``severity`` is the *default* alert severity for the category.  It is a
# human judgement written by the project author, not a number produced by the
# model; the UI labels it that way.
CATEGORY_META: dict[str, dict[str, str]] = {
    "benign":       {"label": "Benign",       "severity": "info",     "color": "#22d3ee"},
    "ddos":         {"label": "DDoS",         "severity": "critical", "color": "#f43f5e"},
    "dos":          {"label": "DoS",          "severity": "high",     "color": "#fb7185"},
    "portscan":     {"label": "Port Scan",    "severity": "medium",   "color": "#f59e0b"},
    "webattack":    {"label": "Web Attack",   "severity": "high",     "color": "#a78bfa"},
    "bruteforce":   {"label": "Brute Force",  "severity": "high",     "color": "#f97316"},
    "bot":          {"label": "Bot / C2",     "severity": "medium",   "color": "#34d399"},
    "infiltration": {"label": "Infiltration", "severity": "critical", "color": "#ef4444"},
    "heartbleed":   {"label": "Heartbleed",   "severity": "critical", "color": "#e11d48"},
}

ATTACK_CATEGORIES = [c for c in CATEGORY_META if c != "benign"]

# Raw CIC-IDS2017 label  ->  SentinelAI category.
# Keys are produced by ``canonical_label()`` (lower-cased, punctuation removed)
# so the mapping still matches copies of the dataset that use a different dash
# character in "Web Attack - Brute Force".
RAW_LABEL_TO_CATEGORY: dict[str, str] = {
    "benign": "benign",
    "ddos": "ddos",
    "dos hulk": "dos",
    "dos goldeneye": "dos",
    "dos slowhttptest": "dos",
    "dos slowloris": "dos",
    "portscan": "portscan",
    "web attack brute force": "webattack",
    "web attack xss": "webattack",
    "web attack sql injection": "webattack",
    "ftp patator": "bruteforce",
    "ssh patator": "bruteforce",
    "bot": "bot",
    "infiltration": "infiltration",
    "heartbleed": "heartbleed",
}

SEVERITY_ORDER = ["critical", "high", "medium", "low", "info"]

_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def canonical_label(raw: object) -> str:
    """
    Normalise a raw dataset label so that two copies of CIC-IDS2017 which use
    a different dash/glyph for ``Web Attack - Brute Force`` still map to the
    same category.

    >>> canonical_label("Web Attack \ufffd Brute Force")
    'web attack brute force'
    """
    text = str(raw if raw is not None else "").strip().lower()
    return _NON_ALNUM.sub(" ", text).strip()


def to_category(raw_label: object) -> str:
    """Map any raw CIC-IDS2017 label onto a SentinelAI attack category."""
    key = canonical_label(raw_label)
    if key in RAW_LABEL_TO_CATEGORY:
        return RAW_LABEL_TO_CATEGORY[key]
    # Unknown label -> keep it visible instead of silently dropping it.
    return key.replace(" ", "") or "unknown"


def category_label(category: str) -> str:
    """Human readable title for a category key."""
    meta = CATEGORY_META.get(category)
    if meta:
        return meta["label"]
    return category.replace("_", " ").title() or "Unknown"


def category_severity(category: str) -> str:
    meta = CATEGORY_META.get(category)
    return meta["severity"] if meta else "medium"


def category_color(category: str) -> str:
    meta = CATEGORY_META.get(category)
    return meta["color"] if meta else "#94a3b8"


def is_attack(category: str) -> bool:
    return category != "benign"
