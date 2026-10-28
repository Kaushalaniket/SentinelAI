"""
Download the CIC-IDS2017 archives used by SentinelAI.

Run it from ``backend/``::

    python -m scripts.download_dataset

It fetches two files into ``backend/data/raw/``:

``MachineLearningCSV.zip`` (235 MB)
    The feature-only CSVs used for **training** the model.

``GeneratedLabelledFlows.zip`` (284 MB)
    The companion file that keeps the original 5-tuple and timestamp, used to
    populate the Traffic Analysis page with real IPs, ports and dates.

Both are re-downloaded only when missing (use ``--force`` to overwrite).
"""

from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path

# Allow `python scripts/download_dataset.py` as well as `-m scripts.download_dataset`.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import config  # noqa: E402

FILES = [
    ("MachineLearningCSV.zip", 235_102_953),
    ("GeneratedLabelledFlows.zip", 283_876_488),
]
BASE = "https://huggingface.co/datasets/bencorn/CICIDS2017/resolve/main/csvs/"


def download(name: str, expected: int, dest_dir: Path, force: bool) -> Path:
    dest = dest_dir / name
    if dest.exists() and dest.stat().st_size == expected and not force:
        print(f"[skip] {name} already present ({dest.stat().st_size:,} bytes)")
        return dest

    url = BASE + name
    tmp = dest.with_suffix(".part")
    print(f"[get ] {name}  <-  {url}")

    def hook(blocks: int, block_size: int, total: int) -> None:
        done = blocks * block_size
        if total > 0:
            pct = min(100, done * 100 // total)
            sys.stdout.write(f"\r       {done:,} / {total:,} bytes ({pct}%)")
            sys.stdout.flush()

    try:
        urllib.request.urlretrieve(url, tmp, reporthook=hook)
    except (urllib.error.URLError, OSError) as exc:
        tmp.unlink(missing_ok=True)
        raise SystemExit(
            f"\n[fail] Could not download {name}: {exc}\n"
            f"       You can also fetch it manually and save it as:\n"
            f"       {dest}"
        ) from exc

    print()
    tmp.rename(dest)
    size = dest.stat().st_size
    if size != expected:
        print(f"[warn] {name} is {size:,} bytes, expected {expected:,}")
    print(f"[ ok ] {dest} ({size:,} bytes)")
    return dest


def main() -> None:
    parser = argparse.ArgumentParser(description="Download the CIC-IDS2017 archives")
    parser.add_argument("--force", action="store_true", help="re-download even if present")
    parser.add_argument("--dir", default=str(config.RAW_DIR))
    args = parser.parse_args()

    dest_dir = Path(args.dir)
    dest_dir.mkdir(parents=True, exist_ok=True)

    for name, size in FILES:
        download(name, size, dest_dir, args.force)

    print(
        "\nDone. Next steps:\n"
        "    python -m scripts.generate_samples     # build the demo CSV files\n"
        "    python -m app.ml.dataset               # build the training sample\n"
        "    python -m app.ml.train                 # train + evaluate the model"
    )


if __name__ == "__main__":
    main()
