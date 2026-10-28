"""
Data cleaning and feature alignment for CIC-IDS2017 style flow CSVs.

The rules implemented here are the ones every intrusion-detection project has
to deal with, because the raw CICFlowMeter output is messy:

* column names have a **leading space** (``" Destination Port"``)
* the header contains a **duplicate** column name (``Fwd Header Length``)
* some cells hold the literal text ``Infinity``
* some cells are empty
* the label column uses a glyph that does not survive every encoding

``align_features`` is the single place that turns an arbitrary uploaded CSV
into the exact numeric matrix the trained model expects.
"""

from __future__ import annotations

import io
import zipfile
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd

# Columns that identify a flow but are *not* model inputs.
IDENTIFIER_COLUMNS = {
    "flow id",
    "source ip",
    "destination ip",
    "source ip address",
    "destination ip address",
    "timestamp",
    "date",
    "comment",
    "label",
}

LABEL_ALIASES = {"label", "class", "attack", "ground truth", "groundtruth", "target"}


# ---------------------------------------------------------------------------
# Column handling
# ---------------------------------------------------------------------------
def strip_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Trim whitespace and de-duplicate column names (pandas suffixes ``.1``)."""
    df = df.copy()
    seen: dict[str, int] = {}
    new_cols: list[str] = []
    for col in df.columns:
        name = str(col).strip()
        if name in seen:
            seen[name] += 1
            name = f"{name}__{seen[name]}"
        else:
            seen[name] = 1
        new_cols.append(name)
    df.columns = new_cols
    return df


def find_label_column(columns: Iterable[str]) -> str | None:
    for col in columns:
        if str(col).strip().lower() in LABEL_ALIASES:
            return col
    return None


def find_identifier_columns(columns: Iterable[str]) -> list[str]:
    out = []
    for col in columns:
        if str(col).strip().lower() in IDENTIFIER_COLUMNS:
            out.append(col)
    return out


# ---------------------------------------------------------------------------
# Reading
# ---------------------------------------------------------------------------
def read_csv_source(source: str | Path | bytes | io.IOBase) -> pd.DataFrame:
    """
    Read a CSV from a path, raw bytes, or an open file object.

    ``latin-1`` is used because the distributed CIC-IDS2017 CSVs contain one
    non-ASCII glyph in the web-attack labels; latin-1 never raises on the
    numeric columns, and :func:`app.ml.labels.canonical_label` normalises the
    label afterwards.
    """
    kwargs = dict(encoding="latin-1", low_memory=False)
    if isinstance(source, bytes):
        return strip_columns(pd.read_csv(io.BytesIO(source), **kwargs))
    if isinstance(source, io.IOBase):
        return strip_columns(pd.read_csv(source, **kwargs))
    return strip_columns(pd.read_csv(Path(source), **kwargs))


def list_zip_csv_members(zip_path: Path) -> list[str]:
    with zipfile.ZipFile(zip_path) as zf:
        return sorted(n for n in zf.namelist() if n.lower().endswith(".csv"))


def read_zip_member(zip_path: Path, member: str) -> pd.DataFrame:
    with zipfile.ZipFile(zip_path) as zf, zf.open(member) as fh:
        return strip_columns(pd.read_csv(fh, encoding="latin-1", low_memory=False))


# ---------------------------------------------------------------------------
# Numeric cleaning
# ---------------------------------------------------------------------------
def clean_numeric(df: pd.DataFrame) -> pd.DataFrame:
    """
    Force every feature column to ``float32`` and replace ``+-inf`` with NaN.

    The model's imputer fills NaN with the median learned from the training
    data, so missing or unparseable cells are handled in exactly one place.
    """
    df = df.copy()
    for col in df.columns:
        df[col] = pd.to_numeric(df[col], errors="coerce")
    arr = df.to_numpy(dtype=np.float32, copy=True)
    arr[~np.isfinite(arr)] = np.nan
    return pd.DataFrame(arr, columns=df.columns, index=df.index).astype(np.float32)


def feature_columns(df: pd.DataFrame) -> list[str]:
    """All columns except identifiers and the label."""
    skip = {c for c in find_identifier_columns(df.columns)}
    return [c for c in df.columns if c not in skip]


def align_features(
    df: pd.DataFrame,
    expected_features: list[str],
) -> tuple[pd.DataFrame, dict]:
    """
    Map an arbitrary input frame onto the model's feature vector.

    Returns ``(matrix, report)`` where ``report`` describes what happened so
    the API can explain it to the user instead of failing silently:

    ``matched``       columns found in the upload and used by the model
    ``missing``       model features absent from the upload (imputed as NaN)
    ``unexpected``    upload columns the model does not use
    ``usable``        True when enough model features were present to score
    """
    numeric_cols = feature_columns(df)
    available = set(numeric_cols)

    matched = [c for c in expected_features if c in available]
    missing = [c for c in expected_features if c not in available]
    unexpected = [c for c in numeric_cols if c not in set(expected_features)]

    # A usable upload must carry at least half of the model's inputs, and at
    # least 10 features - otherwise the prediction would be pure guesswork.
    threshold = max(10, int(0.5 * len(expected_features)))
    usable = len(matched) >= threshold

    matrix = (
        clean_numeric(df[matched])
        if matched
        else pd.DataFrame(columns=expected_features, dtype=np.float32)
    )
    # Reindex so every model feature exists, in model order. Missing ones are
    # NaN and get imputed by the fitted SimpleImputer inside the pipeline.
    matrix = matrix.reindex(columns=expected_features)

    report = {
        "matched": matched,
        "missing": missing,
        "unexpected": unexpected,
        "usable": usable,
        "required_min_features": threshold,
        "expected_count": len(expected_features),
        "matched_count": len(matched),
    }
    return matrix, report
