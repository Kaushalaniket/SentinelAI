"""
Build the labelled training sample from the raw CIC-IDS2017 archive.

The full dataset is 2,830,743 flow records (~885 MB of CSV).  Training on all
of it would take a long time on a laptop, so SentinelAI keeps **every** row of
the rare attack classes and randomly down-samples the very common ones.

The result is written once to ``data/artifacts/train_sample.parquet`` and
re-used by :mod:`app.ml.train`, which makes re-training a matter of seconds.

Run it directly::

    python -m app.ml.dataset
"""

from __future__ import annotations

import argparse
import time

import numpy as np
import pandas as pd

from .. import config
from .labels import to_category
from .preprocess import (
    clean_numeric,
    feature_columns,
    find_label_column,
    list_zip_csv_members,
    read_zip_member,
)


def build_sample(
    zip_path=config.DATASET_ZIP,
    caps: dict[str, int] | None = None,
    per_file_caps: dict[str, int] | None = None,
    seed=config.RANDOM_SEED,
    verbose: bool = True,
) -> pd.DataFrame:
    """
    Stream every CSV inside the CIC-IDS2017 archive and collect a per-category
    down-sampled sample.

    Parameters
    ----------
    caps : maximum number of rows to keep per attack category.  Categories
        with fewer rows than their cap are kept in full.
    per_file_caps : per *file* limit.  ``BENIGN`` traffic exists on all eight
        capture days, so limiting it per file spreads the benign class across
        every day instead of taking all 90k rows from the first file read.
    """
    caps = caps or config.CLASS_CAPS
    per_file_caps = {"benign": 12_000} if per_file_caps is None else per_file_caps
    rng = np.random.default_rng(seed)

    members = list_zip_csv_members(zip_path)
    if not members:
        raise FileNotFoundError(f"No CSV members found inside {zip_path}")

    collected: dict[str, list[pd.DataFrame]] = {}
    kept: dict[str, int] = {}
    seen: dict[str, int] = {}
    per_file: list[dict] = []

    for member in members:
        started = time.time()
        df = read_zip_member(zip_path, member)
        label_col = find_label_column(df.columns)
        if label_col is None:
            if verbose:
                print(f"  ! skipping {member}: no label column")
            continue

        categories = df[label_col].map(to_category)
        feats = feature_columns(df)
        counts: dict[str, int] = {}
        file_kept: dict[str, int] = {}

        for cat in categories.unique():
            total = int((categories == cat).sum())
            seen[cat] = seen.get(cat, 0) + total
            room = caps.get(cat, 2_000) - kept.get(cat, 0)
            if cat in per_file_caps:
                room = min(room, per_file_caps[cat] - file_kept.get(cat, 0))
            if room <= 0:
                counts[cat] = 0
                continue
            block = df.loc[categories == cat, feats]
            if len(block) > room:
                take = rng.choice(len(block), size=room, replace=False)
                block = block.iloc[np.sort(take)]
            collected.setdefault(cat, []).append((cat, block))
            kept[cat] = kept.get(cat, 0) + len(block)
            file_kept[cat] = file_kept.get(cat, 0) + len(block)
            counts[cat] = len(block)

        per_file.append(
            {
                "file": member.split("/")[-1],
                "rows_in_file": len(df),
                "kept": int(sum(counts.values())),
                "labels_found": sorted(counts.keys()),
                "seconds": round(time.time() - started, 1),
            }
        )
        if verbose:
            print(
                f"  {member.split('/')[-1][:52]:54s} "
                f"{len(df):>8,d} rows -> kept {sum(counts.values()):>7,d}"
            )
        del df

    categories_out: list[str] = []
    frames: list[pd.DataFrame] = []
    for cat, blocks in collected.items():
        for block_cat, block in blocks:
            categories_out.extend([block_cat] * len(block))
            frames.append(block)

    sample = pd.concat(frames, axis=0, ignore_index=True)
    sample = clean_numeric(sample)

    # Attach the category label that each row belongs to.
    sample["__category__"] = categories_out

    # Drop constant columns: they cannot help any classifier and only add noise
    # (the CICFlowMeter bulk-average columns are all zero in this dataset).
    nunique = sample.drop(columns=["__category__"]).nunique()
    constant = [c for c in sample.columns if c != "__category__" and nunique[c] <= 1]
    sample = sample.drop(columns=constant)

    if verbose:
        print("\nCategory balance in the training sample:")
        for cat, n in sample["__category__"].value_counts().items():
            print(f"  {cat:14s} {n:>8,d}  (available in dataset: {seen.get(cat, 0):,d})")
        print(f"  TOTAL          {len(sample):>8,d}")
        print(f"\nDropped {len(constant)} constant column(s): {constant}")

    sample.attrs["per_file"] = per_file
    sample.attrs["constant_columns"] = constant
    sample.attrs["rows_seen_in_dataset"] = seen
    return sample


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the SentinelAI training sample")
    parser.add_argument("--zip", default=str(config.DATASET_ZIP))
    parser.add_argument("--out", default=str(config.DATASET_CACHE))
    parser.add_argument("--seed", type=int, default=config.RANDOM_SEED)
    args = parser.parse_args()

    if not config.DATASET_ZIP.exists():
        raise SystemExit(
            f"Dataset archive not found at {config.DATASET_ZIP}\n"
            "Download it first:\n"
            f"    curl -L -o {config.DATASET_ZIP} {config.DATASET_MIRROR_URL}\n"
            "or run:  python -m scripts.download_dataset"
        )

    print(f"Reading {args.zip} ...")
    sample = build_sample(args.zip, seed=args.seed)
    sample.to_parquet(args.out, index=False)
    print(f"\nWrote {args.out} ({len(sample):,} rows, {sample.shape[1]} columns)")


if __name__ == "__main__":
    main()
