"""Thin client for cdx-world-mirror (~/cdx-platform/services/cdx-world-mirror/,
HY-WorldMirror-2.0 wrapper). API per its README/app/main.py, confirmed by
direct read this session:

  POST /generate   multipart: files=@img1.jpg files=@img2.jpg ...
                    Form: target_size (default 952, max 2048), fps (video only)
                    -> {task_id, poll_url, output_dir}
  GET  /tasks/{id}  -> {status: pending|running|done|error, artifacts, ...}
  GET  /tasks/{id}/artifact/{name} -> raw file bytes

POST /generate accepts VidMap cameras two ways: form ``prior_cam_path``
(local Spark path) and uploaded ``prior_cam`` JSON. HY-WorldMirror loads
``{extrinsics:[{camera_id,matrix:4x4 c2w}], intrinsics:[{camera_id,matrix:3x3}]}``.
VidMap writes that shape to rec/vidmap/prior_camera.json. Use
resolve_prior_cam_path() so a recon job prefers VidMap cameras and otherwise
falls back to the bundle's existing approx prior.

splat_train.py still calls this PER SESSION only -- no persistent map --
see gaussian_ply.py's module docstring.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shlex
import time

import requests

# The live cdx-world-mirror instance runs on :7027 (verified 2026-07-10;
# :7025 is occupied by aiq-agent, :7026 by an unrelated Gradio app).
# recon_worker exports WORLD_MIRROR_URL -- honored here because fuse_cell's
# splat path calls train_splat_for_cell without an explicit base_url.
DEFAULT_BASE_URL = os.environ.get("WORLD_MIRROR_URL", "http://127.0.0.1:7027")
POLL_INTERVAL_SECONDS = 1.0
# Same keys WorldMirror's load_prior_camera() and camera_params.json use.
PRIOR_CAM_SCHEMA = "worldmirror_prior_cam_v1"
VIDMAP_PRIOR_REL = Path("rec") / "vidmap" / "prior_camera.json"
APPROX_PRIOR_NAME = "prior_camera.json"


class WorldMirrorError(RuntimeError):
    pass


def extract_extrinsic_matrices(payload: dict) -> list:
    """Camera-to-world 4x4s — the shape reconstruct_session already returns."""
    return [c["matrix"] for c in payload.get("extrinsics", [])]


def validate_prior_cam_payload(payload: dict) -> dict:
    """Accept the HY-WorldMirror prior / camera_params.json contract."""
    if not isinstance(payload, dict):
        raise ValueError("prior_camera.json must be an object")
    extrinsics = payload.get("extrinsics", [])
    intrinsics = payload.get("intrinsics", [])
    if not isinstance(extrinsics, list) or not isinstance(intrinsics, list):
        raise ValueError("prior_camera.json needs extrinsics[] and intrinsics[] lists")
    for entry in extrinsics:
        if not isinstance(entry, dict) or "matrix" not in entry:
            raise ValueError("each extrinsics entry needs a matrix")
        matrix = entry["matrix"]
        if len(matrix) != 4 or any(len(row) != 4 for row in matrix):
            raise ValueError("extrinsics.matrix must be 4x4")
    for entry in intrinsics:
        if not isinstance(entry, dict) or "matrix" not in entry:
            raise ValueError("each intrinsics entry needs a matrix")
        matrix = entry["matrix"]
        if len(matrix) != 3 or any(len(row) != 3 for row in matrix):
            raise ValueError("intrinsics.matrix must be 3x3")
    return payload


def build_prior_cam_payload(
    extrinsics: list[dict],
    intrinsics: list[dict],
    *,
    source: str = "vidmap",
) -> dict:
    payload = {
        "schema": PRIOR_CAM_SCHEMA,
        "source": source,
        "coordinate_system": "opencv_c2w",
        "extrinsics": extrinsics,
        "intrinsics": intrinsics,
    }
    return validate_prior_cam_payload(payload)


def load_prior_cam(path: str | os.PathLike[str]) -> dict:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    return validate_prior_cam_payload(payload)


def _prior_has_cameras(payload: object) -> bool:
    if not isinstance(payload, dict):
        return False
    try:
        validate_prior_cam_payload(payload)
    except ValueError:
        return False
    return bool(payload.get("extrinsics") or payload.get("intrinsics"))


def resolve_prior_cam_path(bundle_dir: str | os.PathLike[str] | None) -> Path | None:
    """VidMap recovered cameras when present; else the bundle approx prior."""
    if bundle_dir is None:
        return None
    root = Path(bundle_dir).expanduser()
    vidmap_prior = root / VIDMAP_PRIOR_REL
    if vidmap_prior.is_file():
        try:
            payload = json.loads(vidmap_prior.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            payload = None
        if _prior_has_cameras(payload):
            return vidmap_prior
    approx = root / APPROX_PRIOR_NAME
    if approx.is_file():
        return approx
    return None


def attach_vidmap_priors(
    manifests_by_id: dict[str, dict],
    *,
    bundle_root: str | os.PathLike[str] | None = None,
) -> dict[str, dict]:
    """Stamp ``bundleDir`` / ``priorCamPath`` from VidMap output onto session manifests.

    Live recon manifests come from R2 and do not know about a local walk bundle.
    Operators set ``VIDMAP_BUNDLE_ROOT`` (or pass ``bundle_root``) so a session
    id maps to ``<root>/<sessionId>/rec/vidmap/prior_camera.json``. Existing
    ``priorCamPath`` / ``bundleDir`` values are left alone.
    """
    root_env = bundle_root if bundle_root is not None else os.environ.get("VIDMAP_BUNDLE_ROOT", "").strip()
    root_path = Path(root_env).expanduser() if root_env else None
    stamped: dict[str, dict] = {}
    for session_id, manifest in manifests_by_id.items():
        copy = dict(manifest)
        if copy.get("priorCamPath"):
            stamped[session_id] = copy
            continue
        candidates: list[Path] = []
        for key in ("bundleDir", "localBundleDir", "captureDir"):
            value = copy.get(key)
            if value:
                candidates.append(Path(str(value)).expanduser())
        if root_path is not None:
            candidates.append(root_path / str(session_id))
            sid = copy.get("sessionId")
            if sid and str(sid) != str(session_id):
                candidates.append(root_path / str(sid))
        for candidate in candidates:
            prior = resolve_prior_cam_path(candidate)
            if prior is None:
                continue
            if prior.name == APPROX_PRIOR_NAME and not (candidate / VIDMAP_PRIOR_REL).is_file():
                # Approx-only bundle: still useful as a prior, but do not pretend
                # VidMap ran. splat_train can use priorCamPath either way.
                copy.setdefault("bundleDir", str(candidate))
                copy["priorCamPath"] = str(prior)
                break
            copy["bundleDir"] = str(candidate)
            copy["priorCamPath"] = str(prior)
            break
        stamped[session_id] = copy
    return stamped


def build_pipeline_argv(
    input_path: str | os.PathLike[str],
    output_path: str | os.PathLike[str],
    *,
    prior_cam_path: str | os.PathLike[str] | None = None,
    prior_depth_path: str | os.PathLike[str] | None = None,
    target_size: int = 952,
    bundle_dir: str | os.PathLike[str] | None = None,
) -> list[str]:
    """HY-WorldMirror CLI with ``--prior_cam_path`` when a prior exists.

    Does not spawn the process — GPU work stays with the caller.
    """
    resolved = prior_cam_path
    if resolved is None and bundle_dir is not None:
        found = resolve_prior_cam_path(bundle_dir)
        resolved = found
    cmd = shlex.split(os.environ.get(
        "WORLD_MIRROR_BIN",
        "python -m hyworld2.worldrecon.pipeline",
    ))
    cmd.extend([
        "--input_path", str(input_path),
        "--strict_output_path", str(output_path),
        "--target_size", str(target_size),
        "--no_interactive",
    ])
    if resolved is not None:
        cmd.extend(["--prior_cam_path", str(resolved)])
    if prior_depth_path is not None:
        cmd.extend(["--prior_depth_path", str(prior_depth_path)])
    return cmd


def submit_generate(
    image_bytes_list: list[bytes],
    target_size: int = 952,
    base_url: str = DEFAULT_BASE_URL,
    prior_cam_path: str | os.PathLike[str] | None = None,
) -> str:
    """Submits 2+ images for a joint 3D reconstruction. Returns task_id."""
    if len(image_bytes_list) < 2:
        raise ValueError("world-mirror needs 2+ multi-view images for a joint solve")
    files = [("files", (f"frame_{i}.jpg", data, "image/jpeg")) for i, data in enumerate(image_bytes_list)]
    data: dict[str, object] = {"target_size": target_size}
    if prior_cam_path is not None:
        data["prior_cam_path"] = str(prior_cam_path)
        prior_file = Path(prior_cam_path)
        if prior_file.is_file():
            files.append((
                "prior_cam",
                (prior_file.name, prior_file.read_bytes(), "application/json"),
            ))
    resp = requests.post(f"{base_url}/generate", files=files, data=data, timeout=30)
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


def reconstruct_session(
    image_bytes_list: list[bytes],
    base_url: str = DEFAULT_BASE_URL,
    target_size: int = 952,
    prior_cam_path: str | os.PathLike[str] | None = None,
    bundle_dir: str | os.PathLike[str] | None = None,
) -> tuple[bytes, list | None]:
    """Full round-trip: submit -> poll -> download gaussians.ply plus the
    per-camera extrinsics (camera_params.json) when available. The
    extrinsics let splat_train Sim3-align the reconstruction (world-mirror's
    own arbitrary frame AND scale) to the ARKit session frame — without
    them, merged community splats don't share a ground plane.

    ``prior_cam_path`` / ``bundle_dir`` select VidMap cameras when present,
    else the bundle's existing approx prior. HTTP /generate uploads the JSON
    as ``prior_cam`` and also sends the Spark path as ``prior_cam_path``.
    """
    if prior_cam_path is None and bundle_dir is not None:
        found = resolve_prior_cam_path(bundle_dir)
        prior_cam_path = found
    task_id = submit_generate(
        image_bytes_list,
        target_size=target_size,
        base_url=base_url,
        prior_cam_path=prior_cam_path,
    )
    artifacts = poll_until_done(task_id, base_url=base_url)
    if "gaussians.ply" not in artifacts:
        raise WorldMirrorError(f"task {task_id} finished without gaussians.ply (got {artifacts})")
    splat = download_artifact(task_id, "gaussians.ply", base_url=base_url)
    cameras = None
    if "camera_params.json" in artifacts:
        try:
            payload = json.loads(download_artifact(task_id, "camera_params.json", base_url=base_url))
            cameras = extract_extrinsic_matrices(payload)
        except Exception:
            cameras = None
    return splat, cameras


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", help="Walk bundle; used to resolve prior_cam_path")
    parser.add_argument("--prior_cam_path", default=None)
    parser.add_argument("--input_path", default=None, help="Images dir for HY CLI argv")
    parser.add_argument("--output_path", default=None)
    parser.add_argument(
        "--print-command",
        action="store_true",
        help="Print the HY-WorldMirror argv (no GPU, no network)",
    )
    args = parser.parse_args(argv)
    prior = args.prior_cam_path
    if prior is None and args.bundle:
        found = resolve_prior_cam_path(args.bundle)
        prior = str(found) if found is not None else None
    if args.print_command:
        if not args.input_path or not args.output_path:
            parser.error("--print-command needs --input_path and --output_path")
        print(" ".join(build_pipeline_argv(
            args.input_path,
            args.output_path,
            prior_cam_path=prior,
            bundle_dir=args.bundle,
        )))
        return 0
    print(json.dumps({"prior_cam_path": prior}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
