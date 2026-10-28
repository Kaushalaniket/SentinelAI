"""
SentinelAI FastAPI application.

Run it with::

    uvicorn app.main:app --reload --port 8000

``GET /`` serves the built React front-end when ``frontend/dist`` exists, so a
single process can host the whole demonstration in production.
"""

from __future__ import annotations

import threading
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import config
from .db import get_db, init_db, utcnow
from .ml.predictor import model_info_safe
from .routers import alerts, dashboard, detection, intel, live, model, reports, settings, traffic

APP_DESCRIPTION = """
SentinelAI - an offline intrusion-detection demonstration system.

* **No live capture.** Traffic comes from the CIC-IDS2017 research dataset and
  from a clearly-labelled synthetic generator.
* **Real machine learning.** The classifier is trained by
  `python -m app.ml.train` on 288,028 labelled CIC-IDS2017 flow records.
* **Honest metrics.** Every score in `/api/model/metrics` is computed on a
  held-out test split that the model never saw during training.
"""

BOOTSTRAP_STATE: dict[str, Any] = {
    "running": False,
    "started_at": None,
    "finished_at": None,
    "steps": [],
    "error": None,
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Create the schema, then populate an empty database in the background."""
    init_db()
    threading.Thread(target=_bootstrap_worker, name="sentinelai-bootstrap", daemon=True).start()
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="SentinelAI API",
        description=APP_DESCRIPTION,
        version="1.0.0",
        contact={"name": "SentinelAI - Final Year CSE Project"},
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=config.CORS_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    for router in (dashboard, live, traffic, detection, model, alerts, intel, reports, settings):
        app.include_router(router.router, prefix=config.API_PREFIX)

    # ------------------------------------------------------------------
    # Health / meta
    # ------------------------------------------------------------------
    @app.get(f"{config.API_PREFIX}/health", tags=["meta"])
    def health() -> dict:
        with get_db() as conn:
            counts = {
                t: conn.execute(f"SELECT COUNT(*) AS n FROM {t}").fetchone()["n"]
                for t in ("traffic_records", "predictions", "alerts", "detection_batches")
            }
            db_error = None
        try:
            model_info_safe()
        except Exception as exc:  # pragma: no cover
            db_error = str(exc)
        try:
            from .live.status import get_live_status

            live_status_info = get_live_status()
            live_ok = bool(live_status_info.get("live_capture_supported"))
        except Exception:
            live_status_info = {}
            live_ok = False
        return {
            "status": "ok",
            "time": utcnow(),
            "database": {"path": str(config.DB_PATH), "counts": counts, "error": db_error},
            "model": {
                "available": model_info_safe().get("available", False),
                "version": model_info_safe().get("model_version"),
                "trained_at": model_info_safe().get("trained_at"),
            },
            "bootstrap": dict(BOOTSTRAP_STATE),
            "live_capture": live_ok,
            "live_status": live_status_info,
        }

    @app.get(f"{config.API_PREFIX}/meta/dataset", tags=["meta"])
    def dataset_meta() -> dict:
        """Provenance of the dataset the model was trained on."""
        return {
            "name": config.DATASET_NAME,
            "publisher": config.DATASET_PUBLISHER,
            "paper": config.DATASET_PAPER,
            "official_url": config.DATASET_OFFICIAL_URL,
            "mirror_url": config.DATASET_MIRROR_URL,
            "total_labelled_rows": config.DATASET_TOTAL_ROWS,
            "rows_used_for_training": 288_028,
            "local_archive": str(config.DATASET_ZIP),
            "archive_present": config.DATASET_ZIP.exists(),
            "live_capture": False,
        }

    # ------------------------------------------------------------------
    # Startup: the lifespan handler above does the work.
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # Static front-end (production build)
    # ------------------------------------------------------------------
    dist = Path(__file__).resolve().parents[2] / "frontend" / "dist"
    if dist.exists() and (dist / "index.html").exists():
        assets = dist / "assets"
        if assets.exists():
            app.mount("/assets", StaticFiles(directory=str(assets)), name="assets")

        @app.get("/{full_path:path}", include_in_schema=False)
        def spa(full_path: str):
            # Unknown API paths must stay JSON 404s - never the HTML shell.
            if full_path == "api" or full_path.startswith("api/"):
                return JSONResponse(
                    status_code=404,
                    content={"detail": f"Unknown API route /{full_path}"},
                )
            candidate = dist / full_path
            if full_path and candidate.is_file():
                return FileResponse(candidate)
            return FileResponse(dist / "index.html")

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=500,
            content={
                "detail": "Unexpected server error",
                "error": str(exc),
                "path": str(request.url.path),
            },
        )

    return app


def _bootstrap_worker() -> None:
    """Populate an empty database in the background so the API starts fast."""
    BOOTSTRAP_STATE.update(
        running=True, started_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        error=None,
    )
    try:
        from .seed import bootstrap

        BOOTSTRAP_STATE["steps"].append("seeding simulated demo traffic")
        report = bootstrap(seed_simulated=True, load_dataset=True, verbose=False)
        BOOTSTRAP_STATE["steps"].append("done")
        BOOTSTRAP_STATE["report"] = {
            k: (v if isinstance(v, (str, dict)) else str(v)) for k, v in report.items()
        }
    except Exception as exc:  # pragma: no cover - surfaced in /api/health
        BOOTSTRAP_STATE["error"] = str(exc)
    finally:
        BOOTSTRAP_STATE["running"] = False
        BOOTSTRAP_STATE["finished_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")


app = create_app()
