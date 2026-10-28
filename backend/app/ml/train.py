"""
Train the SentinelAI intrusion-detection model.

This script performs a genuine supervised-learning experiment:

1. load the labelled CIC-IDS2017 sample produced by :mod:`app.ml.dataset`
2. stratified train/test split (80/20)
3. fit a ``Pipeline``:  median imputation -> standard scaling -> RandomForest
4. evaluate on the **held-out** test split (accuracy, precision, recall, F1,
   per-class report and confusion matrix)
5. compare against two honest baselines (majority-class and multinomial
   logistic regression)
6. persist the fitted pipeline plus every metric to disk

Nothing in this module fabricates a number: every reported figure is computed
from predictions on data the model has never seen.

Run it directly::

    python -m app.ml.train
    python -m app.ml.train --logistic-baseline
"""

from __future__ import annotations

import argparse
import json
import platform
import time
from datetime import datetime, timezone
from typing import Any

import joblib
import pandas as pd
import sklearn
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from .. import config
from .labels import ATTACK_CATEGORIES, category_label
from .preprocess import clean_numeric

TARGET_COL = "__category__"


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------
def load_training_frame() -> tuple[pd.DataFrame, pd.Series, dict[str, Any]]:
    """Return ``(X, y, provenance)`` from the cached sample or the raw zip."""
    if config.DATASET_CACHE.exists():
        df = pd.read_parquet(config.DATASET_CACHE)
        source = str(config.DATASET_CACHE)
    else:
        from .dataset import build_sample

        print("Training sample not cached - building it from the raw archive ...")
        df = build_sample()
        source = str(config.DATASET_ZIP)

    y = df[TARGET_COL].astype(str)
    X = df.drop(columns=[TARGET_COL])
    X = clean_numeric(X)
    provenance = {
        "dataset": config.DATASET_NAME,
        "publisher": config.DATASET_PUBLISHER,
        "paper": config.DATASET_PAPER,
        "official_url": config.DATASET_OFFICIAL_URL,
        "mirror_url": config.DATASET_MIRROR_URL,
        "source_file": source,
        "full_dataset_rows": config.DATASET_TOTAL_ROWS,
        "rows_used_for_training": int(len(df)),
        "class_counts": {k: int(v) for k, v in y.value_counts().items()},
    }
    return X, y, provenance


# ---------------------------------------------------------------------------
# Evaluation
# ---------------------------------------------------------------------------
def evaluate(y_true, y_pred, labels: list[str]) -> dict[str, Any]:
    """Compute a full, honest metric block for one set of predictions."""
    target_names = [category_label(l) for l in labels]
    report = classification_report(
        y_true,
        y_pred,
        labels=labels,
        target_names=target_names,
        output_dict=True,
        zero_division=0,
    )
    cm = confusion_matrix(y_true, y_pred, labels=labels)
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, y_pred)),
        "precision_macro": float(precision_score(y_true, y_pred, average="macro", zero_division=0)),
        "recall_macro": float(recall_score(y_true, y_pred, average="macro", zero_division=0)),
        "f1_macro": float(f1_score(y_true, y_pred, average="macro", zero_division=0)),
        "precision_weighted": float(
            precision_score(y_true, y_pred, average="weighted", zero_division=0)
        ),
        "recall_weighted": float(recall_score(y_true, y_pred, average="weighted", zero_division=0)),
        "f1_weighted": float(f1_score(y_true, y_pred, average="weighted", zero_division=0)),
        "per_class": {
            name: {
                "precision": float(report[name]["precision"]),
                "recall": float(report[name]["recall"]),
                "f1": float(report[name]["f1-score"]),
                "support": int(report[name]["support"]),
            }
            for name in target_names
        },
        "labels": labels,
        "label_names": target_names,
        "confusion_matrix": cm.tolist(),
    }


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------
def build_pipeline(seed: int = config.RANDOM_SEED) -> Pipeline:
    return Pipeline(
        steps=[
            # Median imputation replaces the NaN / +inf cells found in the raw
            # CICFlowMeter output. ``keep_empty_features`` guarantees the matrix
            # keeps its shape even if a column is entirely missing.
            ("imputer", SimpleImputer(strategy="median", keep_empty_features=True)),
            # Scaling is a no-op for tree splits but keeps the pipeline valid if
            # a linear model is swapped in later.
            ("scaler", StandardScaler()),
            (
                "clf",
                RandomForestClassifier(
                    n_estimators=config.N_ESTIMATORS,
                    max_depth=18,
                    max_leaf_nodes=config.MAX_LEAF_NODES,
                    min_samples_leaf=2,
                    max_features="sqrt",
                    class_weight="balanced_subsample",
                    oob_score=False,
                    n_jobs=-1,
                    random_state=seed,
                ),
            ),
        ]
    )


def train(
    seed: int = config.RANDOM_SEED,
    with_logistic_baseline: bool = True,
    verbose: bool = True,
) -> dict[str, Any]:
    """Train, evaluate and persist the model. Returns the metrics dictionary."""
    started = time.time()
    X, y, provenance = load_training_frame()

    labels = sorted(y.unique(), key=lambda c: (c == "benign", c))
    # Guarantee every known category has an explicit slot in the reports, even
    # if the sample happens not to contain it.
    for cat in ATTACK_CATEGORIES:
        if cat not in labels:
            labels.append(cat)

    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=config.TEST_SIZE,
        random_state=seed,
        stratify=y,
    )
    if verbose:
        print(f"Training rows : {len(X_train):,}")
        print(f"Test rows     : {len(X_test):,}  (stratified {int(config.TEST_SIZE*100)}%)")
        print(f"Features      : {X.shape[1]}")
        print(f"Classes       : {len(y.unique())}")

    # ---- main model -----------------------------------------------------
    pipe = build_pipeline(seed)
    t0 = time.time()
    pipe.fit(X_train, y_train)
    train_seconds = time.time() - t0
    y_pred = pipe.predict(X_test)
    model_metrics = evaluate(y_test, y_pred, labels)
    model_metrics["train_seconds"] = round(train_seconds, 2)

    # ---- baselines ------------------------------------------------------
    baselines: dict[str, Any] = {}

    dummy = DummyClassifier(strategy="most_frequent")
    dummy.fit(X_train, y_train)
    baselines["majority_class"] = evaluate(
        y_test, dummy.predict(X_test), labels
    )
    baselines["majority_class"]["description"] = (
        "Always predicts the most frequent training class. "
        "A model must beat this to be useful."
    )

    if with_logistic_baseline:
        try:
            log_pipe = Pipeline(
                steps=[
                    ("imputer", SimpleImputer(strategy="median", keep_empty_features=True)),
                    ("scaler", StandardScaler()),
                    (
                        "clf",
                        LogisticRegression(
                            max_iter=400,
                            random_state=seed,
                            solver="lbfgs",
                        ),
                    ),
                ]
            )
            t0 = time.time()
            log_pipe.fit(X_train, y_train)
            log_seconds = time.time() - t0
            log_metrics = evaluate(y_test, log_pipe.predict(X_test), labels)
            log_metrics["train_seconds"] = round(log_seconds, 2)
            log_metrics["description"] = (
                "Multinomial logistic regression on the same features - a linear "
                "baseline for comparison with the tree ensemble."
            )
            baselines["logistic_regression"] = log_metrics
        except Exception as exc:  # pragma: no cover - baseline is optional
            baselines["logistic_regression"] = {"error": str(exc)}
            if verbose:
                print(f"! logistic-regression baseline failed: {exc}")

    # ---- feature importance --------------------------------------------
    clf = pipe.named_steps["clf"]
    importances = (
        pd.Series(clf.feature_importances_, index=X.columns)
        .sort_values(ascending=False)
        .head(20)
    )
    feature_importances = [
        {"feature": str(f), "importance": float(v)} for f, v in importances.items()
    ]

    # ---- persist ---------------------------------------------------------
    model_version = f"rf-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}"
    feature_names = list(X.columns)

    payload = {
        "pipeline": pipe,
        "feature_names": feature_names,
        "classes": sorted(y.unique()),
        "model_version": model_version,
        "model_type": "RandomForestClassifier (scikit-learn Pipeline)",
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "seed": seed,
        "sklearn_version": sklearn.__version__,
        "provenance": provenance,
        "metrics": model_metrics,
        "baselines": {k: v for k, v in baselines.items() if "error" not in v},
    }
    config.MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(payload, config.MODEL_PATH, compress=3)

    metrics_doc = {
        "model_version": model_version,
        "model_type": payload["model_type"],
        "trained_at": payload["trained_at"],
        "sklearn_version": sklearn.__version__,
        "python_version": platform.python_version(),
        "seed": seed,
        "hyperparameters": {
            "n_estimators": config.N_ESTIMATORS,
            "max_depth": 18,
            "max_leaf_nodes": config.MAX_LEAF_NODES,
            "min_samples_leaf": 2,
            "max_features": "sqrt",
            "class_weight": "balanced_subsample",
            "test_size": config.TEST_SIZE,
        },
        "provenance": provenance,
        "feature_count": len(feature_names),
        "model": model_metrics,
        "baselines": {k: v for k, v in baselines.items() if "error" not in v},
        "feature_importances": feature_importances,
        "elapsed_seconds": round(time.time() - started, 2),
        "model_file_bytes": config.MODEL_PATH.stat().st_size,
        "notes": [
            "All metrics are computed on a held-out 20% stratified test split.",
            "The test split is never used for fitting.",
            "Rare classes (Heartbleed, Infiltration) have very few samples in "
            "CIC-IDS2017, so their per-class scores are statistically weak.",
            "Category colours/severities in the UI are author-defined, not model output.",
        ],
    }
    config.METRICS_PATH.write_text(json.dumps(metrics_doc, indent=2))

    if verbose:
        print(f"\n{'='*66}")
        print(f"RandomForest  accuracy={model_metrics['accuracy']:.4f}  "
              f"macro-F1={model_metrics['f1_macro']:.4f}  "
              f"weighted-F1={model_metrics['f1_weighted']:.4f}")
        for name, base in baselines.items():
            if "error" in base:
                continue
            print(f"  baseline {name:24s} accuracy={base['accuracy']:.4f}  "
                  f"macro-F1={base['f1_macro']:.4f}")
        print("=" * 66)
        print(classification_report(
            y_test, y_pred, labels=labels,
            target_names=[category_label(l) for l in labels],
            zero_division=0, digits=4,
        ))
        print(f"Model saved to {config.MODEL_PATH} "
              f"({config.MODEL_PATH.stat().st_size/1e6:.1f} MB)")
        print(f"Metrics saved to {config.METRICS_PATH}")

    return metrics_doc


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the SentinelAI IDS model")
    parser.add_argument("--seed", type=int, default=config.RANDOM_SEED)
    parser.add_argument(
        "--no-logistic-baseline",
        action="store_true",
        help="skip the logistic-regression baseline (faster)",
    )
    args = parser.parse_args()
    train(
        seed=args.seed,
        with_logistic_baseline=not args.no_logistic_baseline,
    )


if __name__ == "__main__":
    main()
