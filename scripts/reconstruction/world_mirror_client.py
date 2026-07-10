"""Thin client for cdx-world-mirror (~/cdx-platform/services/cdx-world-mirror/,
HY-WorldMirror-2.0 wrapper). API per its README/app/main.py, confirmed by
direct read this session:

  POST /generate   multipart: files=@img1.jpg files=@img2.jpg ...
                    Form: target_size (default 952, max 2048), fps (video only)
                    -> {task_id, poll_url, output_dir}
  GET  /tasks/{id}  -> {status: pending|running|done|error, artifacts, ...}
  GET  /tasks/{id}/artifact/{name} -> raw file bytes

No prior/persistent-map parameter is exposed via this wrapper (confirmed by
reading app/main.py's /generate signature) -- every call is one fresh,
independent joint solve over whatever images it's given. This is why
splat_train.py calls this PER SESSION only, never pooling frames across
sessions -- see gaussian_ply.py's module docstring for the reasoning.
"""
from __future__ import annotations

import time

import requests

DEFAULT_BASE_URL = "http://127.0.0.1:7025"
POLL_INTERVAL_SECONDS = 1.0


class WorldMirrorError(RuntimeError):
    pass


def submit_generate(image_bytes_list: list[bytes], target_size: int = 952, base_url: str = DEFAULT_BASE_URL) -> str:
    """Submits 2+ images for a joint 3D reconstruction. Returns task_id."""
    if len(image_bytes_list) < 2:
        raise ValueError("world-mirror needs 2+ multi-view images for a joint solve")
    files = [("files", (f"frame_{i}.jpg", data, "image/jpeg")) for i, data in enumerate(image_bytes_list)]
    resp = requests.post(f"{base_url}/generate", files=files, data={"target_size": target_size}, timeout=30)
    resp.raise_for_status()
    return resp.json()["task_id"]


def poll_until_done(task_id: str, base_url: str = DEFAULT_BASE_URL, timeout_seconds: float = 300.0) -> list[str]:
    """Polls /tasks/{id} until status is done/error. Returns the artifact
    name list on success; raises WorldMirrorError on failure or timeout."""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        resp = requests.get(f"{base_url}/tasks/{task_id}", timeout=10)
        resp.raise_for_status()
        task = resp.json()
        if task["status"] == "done":
            return task["artifacts"]
        if task["status"] == "error":
            raise WorldMirrorError(f"task {task_id} failed: {task.get('error')}")
        time.sleep(POLL_INTERVAL_SECONDS)
    raise WorldMirrorError(f"task {task_id} did not finish within {timeout_seconds}s")


def download_artifact(task_id: str, name: str, base_url: str = DEFAULT_BASE_URL) -> bytes:
    resp = requests.get(f"{base_url}/tasks/{task_id}/artifact/{name}", timeout=60)
    resp.raise_for_status()
    return resp.content


def reconstruct_session(image_bytes_list: list[bytes], base_url: str = DEFAULT_BASE_URL, target_size: int = 952) -> bytes:
    """Full round-trip: submit -> poll -> download gaussians.ply. This is
    the ONLY entry point splat_train.py needs; the pieces above exist
    separately so a smoke test can exercise each stage independently."""
    task_id = submit_generate(image_bytes_list, target_size=target_size, base_url=base_url)
    artifacts = poll_until_done(task_id, base_url=base_url)
    if "gaussians.ply" not in artifacts:
        raise WorldMirrorError(f"task {task_id} finished without gaussians.ply (got {artifacts})")
    return download_artifact(task_id, "gaussians.ply", base_url=base_url)
