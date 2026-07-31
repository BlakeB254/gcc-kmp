import hashlib
import io

import pytest
from botocore.exceptions import ClientError

import r2_store


class _PreconditionClient:
    def __init__(self, existing: bytes):
        self.existing = existing
        self.put_kwargs = None

    def put_object(self, **kwargs):
        self.put_kwargs = kwargs
        raise ClientError(
            {"Error": {"Code": "PreconditionFailed", "Message": "already exists"}},
            "PutObject",
        )

    def get_object(self, **_kwargs):
        return {"Body": io.BytesIO(self.existing), "ContentLength": len(self.existing)}


def test_conditional_create_race_accepts_only_exact_existing_object(monkeypatch):
    """Catches a concurrent identical retry being mistaken for an overwrite failure."""
    payload = b"immutable-generation"
    fake = _PreconditionClient(payload)
    monkeypatch.setattr(r2_store, "client", lambda: fake)
    monkeypatch.setattr(r2_store, "bucket_name", lambda: "bucket")

    r2_store.upload_bytes_if_absent(
        "reconstructed/1:2/4/verified-candidate/verified.ply",
        payload,
        content_type="application/octet-stream",
        sha256=hashlib.sha256(payload).hexdigest(),
    )

    assert fake.put_kwargs["IfNoneMatch"] == "*"


def test_conditional_create_race_rejects_existing_different_generation(monkeypatch):
    """Catches a precondition race silently accepting same-key different bytes."""
    payload = b"immutable-generation"
    fake = _PreconditionClient(b"different-generation")
    monkeypatch.setattr(r2_store, "client", lambda: fake)
    monkeypatch.setattr(r2_store, "bucket_name", lambda: "bucket")

    with pytest.raises(ValueError, match="SHA-256/size mismatch"):
        r2_store.upload_bytes_if_absent(
            "reconstructed/1:2/4/verified-candidate/verified.ply",
            payload,
            content_type="application/octet-stream",
            sha256=hashlib.sha256(payload).hexdigest(),
        )
