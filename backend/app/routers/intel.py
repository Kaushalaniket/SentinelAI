"""
Threat Intelligence endpoints: IP reputation lookups.

Three sources are supported, in priority order:

1. **AbuseIPDB** - used only when the environment variable
   ``ABUSEIPDB_API_KEY`` is set.  A live HTTPS call.
2. **VirusTotal** - used only when ``VIRUSTOTAL_API_KEY`` is set.
3. **IPsum public blocklist** - the default.  A genuinely public, keyless feed
   that aggregates dozens of open blacklists.  It is downloaded once and cached
   on disk, so lookups keep working with no API key and no internet.

Every response carries an explicit ``is_live`` flag and a ``disclaimer``, so
the UI can never present an offline cache hit as a live API verdict, and can
never present "not in the blocklist" as "safe".
"""

from __future__ import annotations

import ipaddress
import threading
import time
from pathlib import Path
from typing import Any

import httpx
from fastapi import APIRouter, HTTPException, Query

from .. import config
from ..db import get_db, utcnow
from ..schemas import IntelLookupRequest

router = APIRouter(prefix="/intel", tags=["threat-intel"])

_feed_lock = threading.Lock()
_feed_cache: dict[str, Any] | None = None

ABUSEIPDB_URL = "https://api.abuseipdb.com/api/v2/check"
VIRUSTOTAL_URL = "https://www.virustotal.com/api/v3/ip_addresses/{ip}"
REMOTE_TIMEOUT = 8.0

DISCLAIMER = (
    "Not listed in the configured feed does NOT mean an IP is safe - it only "
    "means it was absent from that source at lookup time."
)


# ---------------------------------------------------------------------------
# Offline feed
# ---------------------------------------------------------------------------
def _parse_feed(text: str) -> dict[str, int]:
    entries: dict[str, int] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.replace(",", " ").split()
        if len(parts) < 2:
            continue
        ip, count = parts[0], parts[1]
        try:
            ipaddress.ip_address(ip)
            entries[ip] = int(count)
        except ValueError:
            continue
    return entries


def _file_age_hours(path: Path) -> float | None:
    if not path.exists():
        return None
    return (time.time() - path.stat().st_mtime) / 3600.0


def get_feed(force_refresh: bool = False) -> dict[str, Any]:
    """
    Return the cached blocklist, refreshing it when it is stale.

    Never raises: a failed download falls back to the stale cache, and if there
    is no cache at all the returned dict simply has ``entries = {}`` plus an
    ``error`` explaining why.
    """
    global _feed_cache
    if _feed_cache is not None and not force_refresh:
        return _feed_cache

    with _feed_lock:
        if _feed_cache is not None and not force_refresh:
            return _feed_cache

        age = _file_age_hours(config.TI_FEED_CACHE)
        error: str | None = None
        refreshed = False

        if force_refresh or age is None or age > config.TI_FEED_MAX_AGE_HOURS:
            try:
                resp = httpx.get(
                    config.TI_FEED_URL, timeout=REMOTE_TIMEOUT, follow_redirects=True
                )
                resp.raise_for_status()
                config.TI_FEED_CACHE.write_text(resp.text, encoding="utf-8")
                age = 0.0
                refreshed = True
            except Exception as exc:
                error = f"Could not refresh the threat feed: {exc}"

        text = ""
        if config.TI_FEED_CACHE.exists():
            try:
                text = config.TI_FEED_CACHE.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                error = error or f"Could not read the cached feed: {exc}"

        entries = _parse_feed(text)
        last_update = None
        for line in text.splitlines()[:8]:
            if line.startswith("# Last update:"):
                last_update = line.split(":", 1)[1].strip()
                break

        _feed_cache = {
            "entries": entries,
            "count": len(entries),
            "url": config.TI_FEED_URL,
            "cache_path": str(config.TI_FEED_CACHE),
            "age_hours": round(age, 2) if age is not None else None,
            "last_feed_update": last_update,
            "refreshed_now": refreshed,
            "error": error,
        }
        return _feed_cache


# ---------------------------------------------------------------------------
# Remote providers (optional, key-gated)
# ---------------------------------------------------------------------------
def _check_abuseipdb(ip: str) -> dict[str, Any]:
    key = config.ABUSEIPDB_API_KEY
    if not key:
        raise RuntimeError("ABUSEIPDB_API_KEY is not configured")
    resp = httpx.get(
        ABUSEIPDB_URL,
        params={"ipAddress": ip, "maxAgeInDays": "90"},
        headers={"Key": key, "Accept": "application/json"},
        timeout=REMOTE_TIMEOUT,
    )
    resp.raise_for_status()
    data = resp.json().get("data", {})
    score = int(data.get("abuseConfidenceScore") or 0)
    verdict = "malicious" if score >= 75 else "suspicious" if score >= 25 else "unknown"
    return {
        "source": "AbuseIPDB",
        "is_live": True,
        "verdict": verdict,
        "score": score,
        "detail": (
            f"Abuse confidence {score}% over {data.get('totalReports', 0)} reports. "
            f"ISP: {data.get('isp', 'n/a')}; country: {data.get('countryCode', 'n/a')}; "
            f"Tor exit node: {data.get('isTor', False)}."
        ),
        "categories": [data.get("usageType")] if data.get("usageType") else [],
        "error": None,
    }


def _check_virustotal(ip: str) -> dict[str, Any]:
    key = config.VIRUSTOTAL_API_KEY
    if not key:
        raise RuntimeError("VIRUSTOTAL_API_KEY is not configured")
    resp = httpx.get(
        VIRUSTOTAL_URL.format(ip=ip),
        headers={"x-apikey": key, "Accept": "application/json"},
        timeout=REMOTE_TIMEOUT,
    )
    resp.raise_for_status()
    stats = (
        resp.json().get("data", {}).get("attributes", {}).get("last_analysis_stats", {})
    )
    malicious = int(stats.get("malicious") or 0)
    suspicious = int(stats.get("suspicious") or 0)
    verdict = (
        "malicious" if malicious >= 3 else "suspicious" if (malicious + suspicious) >= 3 else "unknown"
    )
    return {
        "source": "VirusTotal",
        "is_live": True,
        "verdict": verdict,
        "score": min(100, malicious * 5),
        "detail": (
            f"{malicious} engines flagged this IP as malicious, {suspicious} as suspicious, "
            f"{stats.get('harmless', 0)} as harmless out of "
            f"{sum(int(v or 0) for v in stats.values())} engines."
        ),
        "categories": [],
        "error": None,
    }


# ---------------------------------------------------------------------------
# Lookup
# ---------------------------------------------------------------------------
def _offline_check(ip: str) -> dict[str, Any]:
    feed = get_feed()
    ip_obj = ipaddress.ip_address(ip)

    if ip_obj.is_private or ip_obj.is_loopback or ip_obj.is_reserved or ip_obj.is_link_local:
        return {
            "source": "IPsum blocklist (offline cache)",
            "is_live": False,
            "verdict": "private",
            "score": None,
            "detail": (
                "This is a private/reserved address (RFC1918, loopback or link-local), "
                "so public threat feeds do not track it."
            ),
            "categories": [],
            "error": None,
            "feed": _feed_meta(feed),
        }

    hits = feed["entries"].get(ip)
    if hits is None:
        return {
            "source": "IPsum blocklist (offline cache)",
            "is_live": False,
            "verdict": "unknown",
            "score": 0,
            "detail": (
                f"Not present in the cached {feed['count']:,}-entry public blocklist. "
                "Absence from the list is not a guarantee of safety."
            ),
            "categories": [],
            "error": feed.get("error"),
            "feed": _feed_meta(feed),
        }

    verdict = "malicious" if hits >= 10 else "suspicious" if hits >= 3 else "unknown"
    if verdict == "unknown":
        detail = (
            f"Listed in {hits} public blacklist(s) only - a weak signal. "
            f"IPsum aggregates {feed['count']:,} entries "
            f"(last updated {feed.get('last_feed_update') or 'unknown'})."
        )
    else:
        detail = (
            f"Listed in {hits} independent public blacklist(s) aggregated by IPsum "
            f"(feed {feed.get('last_feed_update') or 'no timestamp'}, "
            f"{feed['count']:,} entries)."
        )
    return {
        "source": "IPsum blocklist (offline cache)",
        "is_live": False,
        "verdict": verdict,
        "score": min(100, hits * 3),
        "detail": detail,
        "categories": [f"listed_in_{hits}_blocklists"],
        "error": feed.get("error"),
        "feed": _feed_meta(feed),
    }


def _feed_meta(feed: dict[str, Any]) -> dict[str, Any]:
    return {
        "url": feed.get("url"),
        "entries": feed.get("count"),
        "age_hours": feed.get("age_hours"),
        "last_feed_update": feed.get("last_feed_update"),
        "cache_path": feed.get("cache_path"),
    }


@router.get("/lookup")
def lookup(ip: str = Query(..., max_length=45)) -> dict:
    """
    Reputation lookup for a single IP.

    Remote providers are attempted first (when configured) and any failure is
    reported in ``degradations`` while the offline feed still answers.
    """
    try:
        ip = IntelLookupRequest(ip=ip).ip
    except Exception as exc:
        detail = str(exc)
        if hasattr(exc, "errors"):
            detail = "; ".join(
                e.get("msg", "") for e in exc.errors()  # type: ignore[attr-defined]
            )
        raise HTTPException(422, detail) from exc

    results: list[dict[str, Any]] = []
    degradations: list[str] = []

    for checker, name, configured, env_var in (
        (_check_abuseipdb, "AbuseIPDB", bool(config.ABUSEIPDB_API_KEY), "ABUSEIPDB_API_KEY"),
        (_check_virustotal, "VirusTotal", bool(config.VIRUSTOTAL_API_KEY), "VIRUSTOTAL_API_KEY"),
    ):
        if not configured:
            degradations.append(
                f"{name} API key not configured - skipped. Set the {env_var} "
                "environment variable to enable live lookups."
            )
            continue
        try:
            results.append(checker(ip))
        except Exception as exc:
            degradations.append(f"{name} request failed: {exc}")

    offline = _offline_check(ip)
    results.append(offline)

    # Choose the most authoritative verdict: prefer a live source.
    preferred = next((r for r in results if r["is_live"]), offline)
    overall = preferred["verdict"]

    with get_db() as conn:
        conn.execute(
            """
            INSERT INTO intel_lookups
                (ip, verdict, score, source, is_live, categories, detail, error, queried_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (
                ip,
                overall,
                preferred["score"],
                preferred["source"],
                1 if preferred["is_live"] else 0,
                ",".join(preferred.get("categories") or []),
                preferred["detail"],
                "; ".join(d for d in degradations if "failed" in d.lower()) or None,
                utcnow(),
            ),
        )

    return {
        "ip": ip,
        "verdict": overall,
        "score": preferred["score"],
        "primary_source": preferred["source"],
        "is_live": preferred["is_live"],
        "sources": results,
        "degradations": degradations,
        "disclaimer": DISCLAIMER,
        "queried_at": utcnow(),
        "configured_sources": _configured_sources(),
    }


def _configured_sources() -> list[dict[str, Any]]:
    feed = get_feed()
    return [
        {
            "name": "AbuseIPDB",
            "type": "remote_api",
            "configured": bool(config.ABUSEIPDB_API_KEY),
            "env_var": "ABUSEIPDB_API_KEY",
            "enabled": bool(config.ABUSEIPDB_API_KEY),
        },
        {
            "name": "VirusTotal",
            "type": "remote_api",
            "configured": bool(config.VIRUSTOTAL_API_KEY),
            "env_var": "VIRUSTOTAL_API_KEY",
            "enabled": bool(config.VIRUSTOTAL_API_KEY),
        },
        {
            "name": "IPsum public blocklist",
            "type": "offline_cache",
            "configured": True,
            "env_var": "SENTINELAI_TI_FEED_URL",
            "enabled": True,
            "entries": feed.get("count", 0),
            "age_hours": feed.get("age_hours"),
            "error": feed.get("error"),
        },
    ]


@router.get("/status")
def intel_status() -> dict:
    """Which sources are available and how fresh the offline cache is."""
    feed = get_feed()
    return {
        "sources": _configured_sources(),
        "feed": _feed_meta(feed),
        "feed_error": feed.get("error"),
        "remote_timeout_seconds": REMOTE_TIMEOUT,
        "disclaimer": DISCLAIMER,
        "note": (
            "Set ABUSEIPDB_API_KEY or VIRUSTOTAL_API_KEY as environment variables "
            "to enable live lookups. Without them the app uses the offline public "
            "blocklist only."
        ),
    }


@router.post("/refresh")
def refresh_feed() -> dict:
    """Force a re-download of the offline blocklist."""
    feed = get_feed(force_refresh=True)
    if feed.get("error"):
        return {"status": "degraded", **_feed_meta(feed), "error": feed["error"]}
    return {"status": "ok", **_feed_meta(feed)}


@router.get("/history")
def history(limit: int = Query(25, ge=1, le=200), ip: str | None = None) -> dict:
    with get_db() as conn:
        if ip:
            rows = [
                dict(r)
                for r in conn.execute(
                    "SELECT * FROM intel_lookups WHERE ip = ? "
                    "ORDER BY queried_at DESC LIMIT ?",
                    (ip, limit),
                )
            ]
        else:
            rows = [
                dict(r)
                for r in conn.execute(
                    "SELECT * FROM intel_lookups ORDER BY id DESC LIMIT ?", (limit,)
                )
            ]
    for r in rows:
        r["categories"] = [c for c in (r.get("categories") or "").split(",") if c]
        r["is_live"] = bool(r.get("is_live"))
    return {"items": rows, "total": len(rows)}
