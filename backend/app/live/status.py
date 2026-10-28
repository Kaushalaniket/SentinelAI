"""Check if real packet capture is possible in this environment."""

from __future__ import annotations

import os
import platform
import sys
from typing import Any


def get_live_status() -> dict[str, Any]:
    """Report whether live capture is realistically available."""
    scapy_available = False
    scapy_error = None
    try:
        import scapy  # type: ignore  # noqa: F401

        scapy_available = True
    except Exception as exc:  # pragma: no cover - env dependent
        scapy_error = str(exc)

    is_root = False
    if os.name == "posix":
        try:
            is_root = os.geteuid() == 0  # type: ignore
        except Exception:
            is_root = False
    else:  # pragma: no cover - unlikely in this env
        is_root = True  # best effort

    can_capture = bool(scapy_available and is_root)
    interfaces = []
    if scapy_available:
        try:
            from scapy.arch import get_if_list  # type: ignore

            interfaces = get_if_list()[:10]
        except Exception:
            interfaces = []

    return {
        "live_capture_supported": can_capture,
        "scapy_available": scapy_available,
        "scapy_error": scapy_error,
        "running_as_root": is_root,
        "platform": platform.system(),
        "interfaces": interfaces,
        "mode": "real" if can_capture else "simulated",
        "note": (
            "Real packet capture requires root privileges and libpcap. "
            "Without them, SentinelAI runs in SIMULATED live mode (safe for demos)."
        ),
    }
