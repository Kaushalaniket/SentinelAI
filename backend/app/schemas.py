"""
Pydantic request/response models.

These give the FastAPI endpoints automatic validation *and* generate the
OpenAPI documentation served at ``/docs`` - useful when demonstrating the
project to an examiner.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator

Severity = Literal["critical", "high", "medium", "low", "info"]
Status = Literal["open", "acknowledged", "resolved"]
Origin = Literal["simulated", "dataset", "upload"]


# ---------------------------------------------------------------------------
# Traffic
# ---------------------------------------------------------------------------
class TrafficGenerateRequest(BaseModel):
    """Create simulated demo flows. Always stored with origin='simulated'."""

    count: int = Field(default=50, ge=1, le=2000)
    attack_ratio: float = Field(default=0.35, ge=0.0, le=1.0)


class TrafficQuery(BaseModel):
    page: int = Field(default=1, ge=1)
    page_size: int = Field(default=25, ge=1, le=200)
    origin: Origin | None = None
    category: str | None = None
    severity: str | None = None
    q: str | None = Field(default=None, max_length=120)


# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------
class PredictRowRequest(BaseModel):
    """A single flow record supplied as ``{feature_name: value}`` pairs."""

    features: dict[str, Any] = Field(
        default_factory=dict,
        description="CICFlowMeter feature columns, e.g. {'Destination Port': 80, ...}",
    )

    @field_validator("features")
    @classmethod
    def not_empty(cls, v: dict[str, Any]) -> dict[str, Any]:
        if not v:
            raise ValueError("Provide at least one feature column.")
        return v


class AlertUpdateRequest(BaseModel):
    status: Status


class SettingsUpdateRequest(BaseModel):
    values: dict[str, str] = Field(default_factory=dict)

    @field_validator("values")
    @classmethod
    def sane(cls, v: dict[str, str]) -> dict[str, str]:
        if len(v) > 50:
            raise ValueError("Too many settings in one request.")
        for k, val in v.items():
            if not k or len(k) > 80:
                raise ValueError("Setting keys must be 1-80 characters.")
            if len(val) > 2000:
                raise ValueError(f"Setting '{k}' is too long (max 2000 chars).")
        return v


class IntelLookupRequest(BaseModel):
    ip: str = Field(..., max_length=45)

    @field_validator("ip")
    @classmethod
    def valid_ip(cls, v: str) -> str:
        import ipaddress

        v = v.strip()
        try:
            ipaddress.ip_address(v)
        except ValueError as exc:
            raise ValueError(f"'{v}' is not a valid IPv4 or IPv6 address") from exc
        return v
