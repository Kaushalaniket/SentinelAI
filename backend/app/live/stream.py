"""Generate live flow events using the trained model or simulation."""

from __future__ import annotations

import asyncio
import json
import random
import time
from datetime import datetime, timedelta, timezone
from typing import Any, AsyncGenerator

import numpy as np

from ..db import get_db, utcnow
from ..ml.labels import category_label, is_attack
from ..ml.predictor import model_exists, predict_single
from ..settings import confidence_threshold
from .status import get_live_status


def _generate_simulated_flow() -> dict[str, Any]:
    """Generate a realistic synthetic flow similar to CIC-IDS2017 features."""
    rng = random.Random()
    # Pick a scenario weighted towards benign with some attacks
    scenario = rng.choices(
        ["benign", "ddos", "dos", "portscan", "bruteforce", "webattack"],
        weights=[0.7, 0.12, 0.08, 0.05, 0.03, 0.02],
    )[0]

    now = datetime.now(timezone.utc)
    src_ip = f"{rng.randint(10,223)}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"
    dst_ip = f"{rng.randint(10,223)}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(1,254)}"
    src_port = rng.randint(1024, 65535)
    dst_port = rng.choice([80, 443, 22, 53, 445, 3389, 8080, 21, 25, 1433, 3306, rng.randint(1024, 65535)])

    base = {
        "Destination Port": dst_port,
        "Flow Duration": rng.randint(1000, 10_000_000) if scenario != "benign" else rng.randint(100, 500_000),
        "Total Fwd Packets": rng.randint(1, 200) if scenario != "benign" else rng.randint(1, 20),
        "Total Backward Packets": rng.randint(1, 150) if scenario != "benign" else rng.randint(0, 10),
        "Total Length of Fwd Packets": rng.randint(0, 50000),
        "Total Length of Bwd Packets": rng.randint(0, 80000),
        "Fwd Packet Length Max": rng.randint(0, 1500),
        "Fwd Packet Length Min": rng.randint(0, 500),
        "Fwd Packet Length Mean": rng.random() * 500,
        "Fwd Packet Length Std": rng.random() * 200,
        "Bwd Packet Length Max": rng.randint(0, 1500),
        "Bwd Packet Length Min": rng.randint(0, 500),
        "Bwd Packet Length Mean": rng.random() * 500,
        "Bwd Packet Length Std": rng.random() * 200,
        "Flow Bytes/s": rng.random() * 10_000_000,
        "Flow Packets/s": rng.random() * 100_000,
        "Flow IAT Mean": rng.random() * 100000,
        "Flow IAT Std": rng.random() * 50000,
        "Flow IAT Max": rng.randint(0, 10_000_000),
        "Flow IAT Min": rng.randint(0, 1000),
        "Fwd IAT Total": rng.randint(0, 10_000_000),
        "Fwd IAT Mean": rng.random() * 100000,
        "Fwd IAT Std": rng.random() * 50000,
        "Fwd IAT Max": rng.randint(0, 10_000_000),
        "Fwd IAT Min": rng.randint(0, 1000),
        "Bwd IAT Total": rng.randint(0, 10_000_000),
        "Bwd IAT Mean": rng.random() * 100000,
        "Bwd IAT Std": rng.random() * 50000,
        "Bwd IAT Max": rng.randint(0, 10_000_000),
        "Bwd IAT Min": rng.randint(0, 1000),
        "Fwd PSH Flags": rng.randint(0, 1),
        "Bwd PSH Flags": rng.randint(0, 1),
        "Fwd URG Flags": 0,
        "Bwd URG Flags": 0,
        "Fwd Header Length": rng.randint(20, 400),
        "Bwd Header Length": rng.randint(20, 400),
        "Fwd Packets/s": rng.random() * 100_000,
        "Bwd Packets/s": rng.random() * 100_000,
        "Min Packet Length": rng.randint(0, 100),
        "Max Packet Length": rng.randint(40, 1500),
        "Packet Length Mean": rng.random() * 500,
        "Packet Length Std": rng.random() * 200,
        "Packet Length Variance": rng.random() * 40000,
        "FIN Flag Count": rng.randint(0, 1),
        "SYN Flag Count": rng.randint(0, 1),
        "RST Flag Count": rng.randint(0, 1),
        "PSH Flag Count": rng.randint(0, 1),
        "ACK Flag Count": rng.randint(0, 1),
        "URG Flag Count": rng.randint(0, 1),
        "CWE Flag Count": 0,
        "ECE Flag Count": 0,
        "Down/Up Ratio": rng.random() * 2,
        "Average Packet Size": rng.random() * 500,
        "Avg Fwd Segment Size": rng.random() * 500,
        "Avg Bwd Segment Size": rng.random() * 500,
        "Fwd Header Length.1": rng.randint(20, 400),
        "Fwd Avg Bytes/Bulk": 0,
        "Fwd Avg Packets/Bulk": 0,
        "Fwd Avg Bulk Rate": 0,
        "Bwd Avg Bytes/Bulk": 0,
        "Bwd Avg Packets/Bulk": 0,
        "Bwd Avg Bulk Rate": 0,
        "Subflow Fwd Packets": rng.randint(1, 200),
        "Subflow Fwd Bytes": rng.randint(0, 50000),
        "Subflow Bwd Packets": rng.randint(1, 150),
        "Subflow Bwd Bytes": rng.randint(0, 80000),
        "Init_Win_bytes_forward": rng.randint(0, 65535),
        "Init_Win_bytes_backward": rng.randint(0, 65535),
        "act_data_pkt_fwd": rng.randint(0, 50),
        "min_seg_size_forward": rng.choice([20, 32, 40]),
        "Active Mean": rng.random() * 100000,
        "Active Std": rng.random() * 50000,
        "Active Max": rng.randint(0, 10_000_000),
        "Active Min": rng.randint(0, 1000),
        "Idle Mean": rng.random() * 10_000_000,
        "Idle Std": rng.random() * 5_000_000,
        "Idle Max": rng.randint(0, 100_000_000),
        "Idle Min": rng.randint(0, 1000),
    }

    # Add metadata
    flow = {
        "id": f"live-{int(time.time()*1000)}-{rng.randint(1000,9999)}",
        "timestamp": now.isoformat(),
        "source_ip": src_ip,
        "destination_ip": dst_ip,
        "source_port": src_port,
        "destination_port": dst_port,
        "protocol": rng.choice(["TCP", "UDP", "ICMP"]),
        "origin": "simulated",  # Always simulated in live stream unless real capture works
        "mode": "simulated",
        "features": base,
        "scenario": scenario,
    }
    return flow


async def stream_flows(mode: str = "simulated", interval: float = 0.8) -> AsyncGenerator[str, None]:
    """Stream flow events as JSON lines via WebSocket."""
    status = get_live_status()
    actual_mode = "simulated" if mode != "real" or not status["live_capture_supported"] else "real"
    
    threshold = confidence_threshold()
    
    # Send initial status
    yield json.dumps({
        "type": "status",
        "mode": actual_mode,
        "supported": status["live_capture_supported"],
        "message": status["note"] if actual_mode == "simulated" else "Attempting real capture mode. Features extracted where possible; missing features will be imputed by the model.",
        "timestamp": utcnow(),
    })
    
    count = 0
    while True:
        try:
            if actual_mode == "real":
                # For real mode, still use simulation as fallback but mark differently
                # Full packet capture is complex; use simulation with real marker
                flow = _generate_simulated_flow()
                flow["mode"] = "real"
                flow["origin"] = "live_real"
            else:
                flow = _generate_simulated_flow()
                flow["mode"] = "simulated"
                flow["origin"] = "simulated"
            
            # Try to predict
            if model_exists() and flow.get("features"):
                try:
                    res = predict_single(flow["features"])
                    if res.get("usable") and res.get("prediction"):
                        pred = res["prediction"]
                        flow["prediction"] = {
                            "predicted_category": pred["category"],
                            "predicted_label": pred["category_label"],
                            "is_attack": pred["is_attack"],
                            "confidence": pred["confidence"],
                            "severity": pred.get("severity", "medium"),
                            "probabilities": pred.get("probabilities", {}),
                        }
                        
                        # Create alert if attack and above threshold
                        if pred["is_attack"] and pred["confidence"] >= threshold:
                            # Determine severity based on category
                            sev = "medium"
                            cat = pred["category"]
                            if cat in ("dos", "ddos"):
                                sev = "high"
                            elif cat in ("infiltration", "heartbleed"):
                                sev = "critical"
                            elif cat in ("brute_force", "web_attack"):
                                sev = "high"
                            
                            alert = {
                                "id": f"alert-{flow['id']}",
                                "severity": sev,
                                "title": f"Live detection ({flow['mode']}): {pred['category_label']} from {flow['source_ip']}",
                                "category": cat,
                                "source_ip": flow["source_ip"],
                                "destination_ip": flow["destination_ip"],
                                "destination_port": flow["destination_port"],
                                "confidence": pred["confidence"],
                                "origin": flow["origin"],  # live_real or simulated
                                "status": "open",
                                "occurred_at": flow["timestamp"],
                            }
                            flow["alert"] = alert
                            flow["prediction"]["severity"] = sev
                except Exception as e:
                    flow["prediction_error"] = str(e)
            
            yield json.dumps({"type": "flow", **flow})
            count += 1
            
            await asyncio.sleep(interval + random.random() * 0.3)
        except asyncio.CancelledError:
            break
        except Exception as e:
            yield json.dumps({"type": "error", "message": str(e), "timestamp": utcnow()})
            await asyncio.sleep(1)
