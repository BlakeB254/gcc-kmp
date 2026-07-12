"""R2 access for the fusion pipeline — reads raw session manifests/frames for
a location cell, writes fused outputs back. Read-only against `raw/`, the
mobile app's namespace; this pipeline only ever writes under `reconstructed/`
(and the privacy scrub worker, scripts/privacy_scrub/, under `scrubbed/`).

Env vars (same names as site/src/lib/r2.ts, so one bucket/one set of
credentials serves both the app and this pipeline):
  R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass

import boto3

_LOCATION_CELL_ID_PATTERN = re.compile(r"^-?\d+:-?\d+$")


class R2NotConfiguredError(RuntimeError):
    pass


def _env() -> dict:
    return {
        "account_id": os.environ.get("R2_ACCOUNT_ID"),
        "access_key_id": os.environ.get("R2_ACCESS_KEY_ID"),
        "secret_access_key": os.environ.get("R2_SECRET_ACCESS_KEY"),
        "bucket_name": os.environ.get("R2_BUCKET_NAME"),
    }


def is_configured() -> bool:
    e = _env()
    return all(e.values())


def client():
    e = _env()
    if not is_configured():
        raise R2NotConfiguredError(
            "R2 is not configured — set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, "
            "R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME"
        )
    return boto3.client(
        "s3",
        endpoint_url=f"https://{e['account_id']}.r2.cloudflarestorage.com",
        aws_access_key_id=e["access_key_id"],
        aws_secret_access_key=e["secret_access_key"],
        region_name="auto",
    )


def bucket_name() -> str:
    name = _env()["bucket_name"]
    if not name:
        raise R2NotConfiguredError("R2_BUCKET_NAME is not set")
    return name


def validate_cell_id(cell_id: str) -> str:
    """Same guard as site/src/lib/r2.ts's rawScanKey — an R2 prefix built
    from this must never contain a path-escaping component."""
    if not _LOCATION_CELL_ID_PATTERN.match(cell_id):
        raise ValueError(f"invalid locationCellId: {cell_id!r}")
    return cell_id


@dataclass
class ManifestObject:
    key: str
    manifest: dict


def list_manifests_for_cell(cell_id: str) -> list[ManifestObject]:
    """Every env-scan session manifest uploaded for this cell, across all
    devices. Manifest keys end `-manifest.json` (EnvScanSessionUploader's
    convention); everything else under the cell prefix is a photo or
    captured frame, referenced BY the manifest rather than listed directly.
    """
    validate_cell_id(cell_id)
    c = client()
    prefix = f"raw/{cell_id}/"
    manifests: list[ManifestObject] = []
    continuation = None
    while True:
        kwargs = {"Bucket": bucket_name(), "Prefix": prefix}
        if continuation:
            kwargs["ContinuationToken"] = continuation
        resp = c.list_objects_v2(**kwargs)
        for obj in resp.get("Contents", []):
            key = obj["Key"]
            if key.endswith("-manifest.json"):
                body = c.get_object(Bucket=bucket_name(), Key=key)["Body"].read()
                manifests.append(ManifestObject(key=key, manifest=json.loads(body)))
        if resp.get("IsTruncated"):
            continuation = resp["NextContinuationToken"]
        else:
            break
    return manifests


def download_bytes(key: str) -> bytes:
    c = client()
    return c.get_object(Bucket=bucket_name(), Key=key)["Body"].read()


SCRUBBED_PREFIX = "scrubbed/"
_RAW_PREFIX = "raw/"


def scrubbed_key_for(raw_key: str) -> str:
    """Privacy-scrubbed rendition key for a raw capture artifact: same key
    with the leading 'raw/' segment swapped for 'scrubbed/'. This is THE
    naming contract the review UI and the scrub worker share — change it in
    lockstep with scripts/privacy_scrub/ and any UI preference logic."""
    if not raw_key.startswith(_RAW_PREFIX):
        raise ValueError(f"not a raw/ capture key: {raw_key!r}")
    return SCRUBBED_PREFIX + raw_key[len(_RAW_PREFIX):]


def object_exists(key: str) -> bool:
    c = client()
    try:
        c.head_object(Bucket=bucket_name(), Key=key)
        return True
    except c.exceptions.ClientError:
        return False


def download_video_bytes(raw_key: str) -> bytes:
    """Download a capture video chunk, preferring its privacy-scrubbed
    rendition when one exists (PRD docs/prd/scan-session-replay-devops-prd.md
    decision #2: scrubbed imagery feeds reconstruction and review surfaces;
    raw stays admin-only). PREFER_SCRUBBED_VIDEO=0 is the kill switch."""
    if os.environ.get("PREFER_SCRUBBED_VIDEO", "1") != "0" and is_configured():
        try:
            candidate = scrubbed_key_for(raw_key)
        except ValueError:
            candidate = None
        if candidate and object_exists(candidate):
            return download_bytes(candidate)
    return download_bytes(raw_key)


def upload_bytes(key: str, data: bytes, content_type: str) -> None:
    c = client()
    c.put_object(Bucket=bucket_name(), Key=key, Body=data, ContentType=content_type)


def reconstructed_asset_key(cell_id: str, version: int, asset_type: str, fmt: str) -> str:
    """Mirrors site/src/lib/r2.ts's reconstructedAssetKey exactly — same
    layout, so the app's admin tooling and this pipeline agree on paths
    without either side hard-coding the other's output."""
    validate_cell_id(cell_id)
    return f"reconstructed/{cell_id}/{version}/{asset_type}.{fmt}"


_VERSION_KEY_PATTERN = re.compile(r"^reconstructed/[^/]+/(\d+)/")


def next_run_version_for_cell(cell_id: str) -> int:
    """One version directory per worker RUN, shared by every asset type in
    that run (reconstructed/{cell}/{version}/{assetType}.{fmt} share the
    version path segment) -- so the next run version is max across ALL
    asset types + 1. Same R2-listing-as-source-of-truth rationale and same
    narrow same-instant race caveat as next_version_for_cell below; the
    queue's per-cell job closing makes two simultaneous runs for one cell
    effectively impossible in the worker's operating model."""
    validate_cell_id(cell_id)
    c = client()
    prefix = f"reconstructed/{cell_id}/"
    highest = 0
    continuation = None
    while True:
        kwargs = {"Bucket": bucket_name(), "Prefix": prefix}
        if continuation:
            kwargs["ContinuationToken"] = continuation
        resp = c.list_objects_v2(**kwargs)
        for obj in resp.get("Contents", []):
            match = _VERSION_KEY_PATTERN.match(obj["Key"])
            if match:
                highest = max(highest, int(match.group(1)))
        if resp.get("IsTruncated"):
            continuation = resp["NextContinuationToken"]
        else:
            break
    return highest + 1


def next_version_for_cell(cell_id: str, asset_type: str) -> int:
    """R2's own object listing is the source of truth for "what physical
    versions already exist" -- avoids a DB round-trip (this pipeline
    otherwise never talks to Postgres directly, only through the admin API)
    at the cost of a real but narrow race: two fuse_cell.py runs for the
    SAME cell at the SAME instant could both compute the same next version
    and collide. Acceptable for this tool's actual operating model (a
    periodic single-run batch job, not a concurrent web service) -- if that
    ever changes, this needs a proper atomic reservation instead."""
    validate_cell_id(cell_id)
    c = client()
    prefix = f"reconstructed/{cell_id}/"
    highest = 0
    continuation = None
    while True:
        kwargs = {"Bucket": bucket_name(), "Prefix": prefix}
        if continuation:
            kwargs["ContinuationToken"] = continuation
        resp = c.list_objects_v2(**kwargs)
        for obj in resp.get("Contents", []):
            if f"/{asset_type}." not in obj["Key"]:
                continue
            match = _VERSION_KEY_PATTERN.match(obj["Key"])
            if match:
                highest = max(highest, int(match.group(1)))
        if resp.get("IsTruncated"):
            continuation = resp["NextContinuationToken"]
        else:
            break
    return highest + 1
