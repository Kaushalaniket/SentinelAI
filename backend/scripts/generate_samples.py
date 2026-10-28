"""
Generate the demo CSV files offered on the AI Detection page.

Run from ``backend/``::

    python -m scripts.generate_samples

Three files are written to ``backend/data/samples/``:

``cicids2017_sample_mixed.csv``
    150 real CIC-IDS2017 flow records spread across every attack category.
    Score it to show correct multi-class predictions.

``cicids2017_with_missing_values.csv``
    80 real records where blank cells and ``Infinity`` values were injected on
    purpose, to demonstrate the imputation step.

``invalid_too_few_columns.csv``
    A deliberately broken file containing only a handful of columns. The API
    rejects it, which is useful for demonstrating input validation.

Nothing here is fabricated traffic: all rows are genuine dataset records.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import config  # noqa: E402
from app.ml.labels import to_category  # noqa: E402
from app.ml.preprocess import (  # noqa: E402
    feature_columns,
    find_label_column,
    list_zip_csv_members,
    read_zip_member,
)

PER_CATEGORY = {
    "benign": 40,
    "ddos": 16,
    "dos": 16,
    "portscan": 16,
    "bruteforce": 14,
    "webattack": 12,
    "bot": 10,
    "infiltration": 10,
    "heartbleed": 6,
}


def collect(seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    picked: dict[str, list[pd.DataFrame]] = {}
    need = dict(PER_CATEGORY)

    for member in list_zip_csv_members(config.DATASET_ZIP):
        if not any(v > 0 for v in need.values()):
            break
        df = read_zip_member(config.DATASET_ZIP, member)
        label_col = find_label_column(df.columns)
        if label_col is None:
            continue
        cats = df[label_col].map(to_category)
        for cat, want in list(need.items()):
            if want <= 0:
                continue
            idx = np.flatnonzero((cats == cat).to_numpy())
            if idx.size == 0:
                continue
            take = min(want, idx.size)
            chosen = np.sort(rng.choice(idx, size=take, replace=False))
            picked.setdefault(cat, []).append(df.iloc[chosen])
            need[cat] -= take
        del df

    out = pd.concat([b for blocks in picked.values() for b in blocks], ignore_index=True)
    return out.sample(frac=1, random_state=seed).reset_index(drop=True)


def main() -> None:
    if not config.DATASET_ZIP.exists():
        raise SystemExit(
            f"Dataset archive missing at {config.DATASET_ZIP}\n"
            "Run:  python -m scripts.download_dataset"
        )

    config.SAMPLE_DIR.mkdir(parents=True, exist_ok=True)
    print("Collecting a stratified sample of real CIC-IDS2017 records ...")
    df = collect()
    print(f"  collected {len(df)} rows across {df['Label'].nunique()} raw labels")

    # 1. clean sample -----------------------------------------------------
    clean_path = config.SAMPLE_DIR / "cicids2017_sample_mixed.csv"
    df.to_csv(clean_path, index=False)
    print(f"  wrote {clean_path} ({clean_path.stat().st_size:,} bytes)")

    # 2. sample with injected missing values ------------------------------
    dirty = df.copy()
    rng = np.random.default_rng(11)
    cols = feature_columns(dirty)

    # blank cells
    for col in rng.choice(cols, size=12, replace=False):
        rows = rng.choice(len(dirty), size=max(1, len(dirty) // 12), replace=False)
        dirty.loc[rows, col] = np.nan

    # literal "Infinity" values, exactly as CICFlowMeter sometimes emits
    for col in rng.choice(cols, size=4, replace=False):
        dirty[col] = dirty[col].astype("float64")
        rows = rng.choice(len(dirty), size=3, replace=False)
        dirty.loc[rows, col] = np.inf

    dirty_path = config.SAMPLE_DIR / "cicids2017_with_missing_values.csv"
    dirty.to_csv(dirty_path, index=False)
    print(f"  wrote {dirty_path} ({dirty_path.stat().st_size:,} bytes)")

    # 3. deliberately invalid file ----------------------------------------
    bad = df.head(20)[["Destination Port", "Flow Duration", "Label"]].copy()
    bad["Unrelated Column"] = "not a CIC feature"
    bad_path = config.SAMPLE_DIR / "invalid_too_few_columns.csv"
    bad.to_csv(bad_path, index=False)
    print(f"  wrote {bad_path} ({bad_path.stat().st_size:,} bytes)")

    counts = df[find_label_column(df.columns)].map(to_category).value_counts()
    print("\nCategory balance in the clean sample:")
    for cat, n in counts.items():
        print(f"  {cat:14s} {n:>4d}")
    print("\nDone. These files appear on the AI Detection page.")


if __name__ == "__main__":
    main()
