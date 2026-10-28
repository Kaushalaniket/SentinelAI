"""Real packet capture using scapy with best-effort feature extraction.

This attempts to extract compatible features for the CIC-IDS2017 trained model.
Full CICFlowMeter-style extraction is complex; we compute what we can and let
the model's imputer handle missing features.
"""

from __future__ import annotations

import time
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Dict, Optional

from .status import get_live_status


class FlowTracker:
    def __init__(self):
        self.flows = defaultdict(lambda: {
            'packets_fwd': 0,
            'packets_bwd': 0,
            'bytes_fwd': 0,
            'bytes_bwd': 0,
            'start_time': time.time(),
            'last_time': time.time(),
            'fwd_lengths': [],
            'bwd_lengths': [],
            'fwd_times': [],
            'bwd_times': [],
        })

    def update(self, pkt):
        try:
            from scapy.layers.inet import IP, TCP, UDP  # type: ignore
        except Exception:
            return

        if IP not in pkt:
            return
        ip = pkt[IP]
        src = ip.src
        dst = ip.dst

        sport = dport = 0
        proto = ip.proto
        if TCP in pkt:
            sport = pkt[TCP].sport
            dport = pkt[TCP].dport
            proto_name = 'TCP'
        elif UDP in pkt:
            sport = pkt[UDP].sport
            dport = pkt[UDP].dport
            proto_name = 'UDP'
        else:
            proto_name = 'Other'

        key_fwd = (src, dst, sport, dport, proto)
        key_bwd = (dst, src, dport, sport, proto)
        now = time.time()

        if key_fwd in self.flows or (src, dst) == (list(self.flows.keys())[0][:2] if self.flows else (None, None)):
            # Try to classify direction
            flow = self.flows[key_fwd]
            flow['packets_fwd'] += 1
            flow['bytes_fwd'] += len(pkt)
            flow['fwd_lengths'].append(len(pkt))
            flow['fwd_times'].append(now)
            flow['last_time'] = now
        else:
            flow = self.flows[key_bwd]
            flow['packets_bwd'] += 1
            flow['bytes_bwd'] += len(pkt)
            flow['bwd_lengths'].append(len(pkt))
            flow['bwd_times'].append(now)
            flow['last_time'] = now

    def extract_features(self, key, flow, pkt_info=None):
        """Extract features compatible with model schema."""
        src, dst, sport, dport, proto = key
        duration = max((flow['last_time'] - flow['start_time']) * 1e6, 1)  # microseconds
        total_fwd = flow['packets_fwd']
        total_bwd = flow['packets_bwd']

        features = {}
        features['Destination Port'] = dport
        features['Flow Duration'] = duration
        features['Total Fwd Packets'] = total_fwd
        features['Total Backward Packets'] = total_bwd
        features['Total Length of Fwd Packets'] = flow['bytes_fwd']
        features['Total Length of Bwd Packets'] = flow['bytes_bwd']

        if flow['fwd_lengths']:
            features['Fwd Packet Length Max'] = max(flow['fwd_lengths'])
            features['Fwd Packet Length Min'] = min(flow['fwd_lengths'])
            features['Fwd Packet Length Mean'] = sum(flow['fwd_lengths']) / len(flow['fwd_lengths'])
            import statistics
            try:
                features['Fwd Packet Length Std'] = statistics.stdev(flow['fwd_lengths']) if len(flow['fwd_lengths']) > 1 else 0
            except Exception:
                features['Fwd Packet Length Std'] = 0
        else:
            features['Fwd Packet Length Max'] = features['Fwd Packet Length Min'] = features['Fwd Packet Length Mean'] = features['Fwd Packet Length Std'] = 0

        if flow['bwd_lengths']:
            features['Bwd Packet Length Max'] = max(flow['bwd_lengths'])
            features['Bwd Packet Length Min'] = min(flow['bwd_lengths'])
            features['Bwd Packet Length Mean'] = sum(flow['bwd_lengths']) / len(flow['bwd_lengths'])
            import statistics
            try:
                features['Bwd Packet Length Std'] = statistics.stdev(flow['bwd_lengths']) if len(flow['bwd_lengths']) > 1 else 0
            except Exception:
                features['Bwd Packet Length Std'] = 0
        else:
            features['Bwd Packet Length Max'] = features['Bwd Packet Length Min'] = features['Bwd Packet Length Mean'] = features['Bwd Packet Length Std'] = 0

        # Fill common derived features
        features['Flow Bytes/s'] = (flow['bytes_fwd'] + flow['bytes_bwd']) / max(duration / 1e6, 0.001)
        features['Flow Packets/s'] = (total_fwd + total_bwd) / max(duration / 1e6, 0.001)
        features['Fwd Packets/s'] = total_fwd / max(duration / 1e6, 0.001)
        features['Bwd Packets/s'] = total_bwd / max(duration / 1e6, 0.001)

        # Default zeros for remaining features - model imputer will handle
        return features, src, dst, sport, dport, proto_name if 'proto_name' in dir() else str(proto)


def start_real_capture(interface: Optional[str] = None, callback=None):
    """Start real packet capture if possible."""
    status = get_live_status()
    if not status['live_capture_supported']:
        raise RuntimeError("Real capture not supported in this environment")

    try:
        from scapy.all import sniff  # type: ignore
        from scapy.layers.inet import IP, TCP, UDP  # type: ignore
    except Exception as e:
        raise RuntimeError(f"Scapy not available: {e}")

    tracker = FlowTracker()

    def packet_handler(pkt):
        try:
            tracker.update(pkt)
            # For demo, we process periodically
            if callback:
                # This is simplified - in practice we'd aggregate flows
                pass
        except Exception:
            pass

    # Note: This is a simplified implementation
    sniff(iface=interface, prn=packet_handler, store=False, timeout=1)
    return tracker
