"""Live monitor endpoints: status and WebSocket stream."""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query

from ..live.status import get_live_status
from ..live.stream import stream_flows

router = APIRouter(prefix="/live", tags=["live"])


@router.get("/status")
def live_status() -> dict[str, Any]:
    """Report live capture availability."""
    status = get_live_status()
    return {
        **status,
        "description": "Live Monitor streams network flows and scores them with the trained ML model.",
    }


@router.websocket("/ws")
async def live_websocket(websocket: WebSocket, mode: str = Query("simulated")):
    """WebSocket stream of live flows."""
    await websocket.accept()
    try:
        async for event in stream_flows(mode=mode, interval=0.7):
            await websocket.send_text(event)
    except WebSocketDisconnect:
        pass
    except Exception as e:
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
        except Exception:
            pass
    finally:
        try:
            await websocket.close()
        except Exception:
            pass
