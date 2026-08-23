#!/usr/bin/env python3
"""
Convert a raw scan capture asset (PLY point cloud, OBJ mesh — USDZ not yet
supported, see below) into a GLB usable in Blender/general 3D tooling on
this DGX Spark, and optionally publish the result to R2 + register it in
Postgres via the admin API.

The ORIGINAL FILE IS NEVER MODIFIED OR DELETED — conversion always writes a
new file; nothing here touches --in beyond reading it.

Conversion runs Blender headless as a subprocess (bpy is only importable
from Blender's own bundled Python, not this interpreter) — see
scripts/_blender_convert_worker.py for the actual import/export calls.

Known gap: this Blender install (4.0.2, verified 2026-07-03) has no USD
add-on registered, so .usdz/.usdc/.usda inputs are rejected with a clear
error rather than silently mishandled. RoomPlan/ARKit USDZ capture isn't
produced by any shipped mobile code yet either (see
docs/superpowers/specs/2026-07-02-scan-guidance-world-model-and-rewards-
design.md) — when that lands, USDZ support needs a USD-capable Blender
build or a separate USD toolchain step ahead of this script.

Usage
-----
    # Local conversion only — writes alongside the input by default.
    python3 scripts/convert-scan-asset.py --in raw_scan.ply

    # Convert AND publish: upload the GLB to R2 and register it against a
    # location cell so it shows up in the admin scan visualizer.
    python3 scripts/convert-scan-asset.py \\
        --in raw_scan.ply \\
        --upload \\
        --location-cell-id 123:-456 \\
        --asset-type pointcloud \\
        --version 1 \\
        --source-coverage-event-ids 101,102,103 \\
        --admin-token "$GCC_ADMIN_TOKEN"
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

import boto3
import requests

SCRIPT_DIR = Path(__file__).parent.resolve()
BLENDER_WORKER = SCRIPT_DIR / '_blender_convert_worker.py'
DEFAULT_API_BASE_URL = 'https://api.globalcommunitycleaners.org'
LOCATION_CELL_ID_RE_HINT = 'e.g. "123:-456" (matches gridCell() in site/src/lib/world-observations.ts)'


def convert_to_glb(input_path: Path, output_path: Path) -> None:
    """Shells out to Blender headless — the only Python that can `import bpy`."""
    result = subprocess.run(
        ['blender', '--background', '--python', str(BLENDER_WORKER), '--', '--in', str(input_path), '--out', str(output_path)],
        capture_output=True,
        text=True,
    )
    # Blender's own startup banner goes to stdout regardless of outcome —
    # only print it when something went wrong, so success stays quiet.
    if result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise RuntimeError(f'Blender conversion failed (exit {result.returncode}) for {input_path}')
    if not output_path.exists():
        raise RuntimeError(f'Blender reported success but {output_path} was not created')


def upload_to_r2(local_path: Path, r2_key: str) -> None:
    account_id = os.environ.get('R2_ACCOUNT_ID')
    access_key_id = os.environ.get('R2_ACCESS_KEY_ID')
    secret_access_key = os.environ.get('R2_SECRET_ACCESS_KEY')
    bucket_name = os.environ.get('R2_BUCKET_NAME')
    missing = [
        name for name, value in [
            ('R2_ACCOUNT_ID', account_id),
            ('R2_ACCESS_KEY_ID', access_key_id),
            ('R2_SECRET_ACCESS_KEY', secret_access_key),
            ('R2_BUCKET_NAME', bucket_name),
        ] if not value
    ]
    if missing:
        raise RuntimeError(f'R2 not configured — missing env vars: {", ".join(missing)}')

    client = boto3.client(
        's3',
        endpoint_url=f'https://{account_id}.r2.cloudflarestorage.com',
        aws_access_key_id=access_key_id,
        aws_secret_access_key=secret_access_key,
        region_name='auto',
    )
    client.upload_file(str(local_path), bucket_name, r2_key, ExtraArgs={'ContentType': 'model/gltf-binary'})


def register_reconstruction_asset(
    api_base_url: str,
    admin_token: str,
    location_cell_id: str,
    asset_type: str,
    format_: str,
    r2_key: str,
    version: int,
    source_scan_coverage_event_ids: list[int],
) -> dict:
    response = requests.post(
        f'{api_base_url}/api/admin/reconstruction-assets',
        headers={'Authorization': f'Bearer {admin_token}'},
        json={
            'locationCellId': location_cell_id,
            'assetType': asset_type,
            'format': format_,
            'r2Key': r2_key,
            'version': version,
            'sourceScanCoverageEventIds': source_scan_coverage_event_ids,
        },
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(f'Failed to register reconstruction asset: {response.status_code} {response.text}')
    return response.json()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--in', dest='input_path', required=True, type=Path, help='Raw scan asset (.ply or .obj)')
    parser.add_argument('--out', dest='output_path', type=Path, help='Output GLB path (default: alongside input, same stem + .glb)')
    parser.add_argument('--upload', action='store_true', help='Also upload the GLB to R2 and register it in reconstruction_assets')
    parser.add_argument('--location-cell-id', help=f'Required with --upload. {LOCATION_CELL_ID_RE_HINT}')
    parser.add_argument('--asset-type', choices=['pointcloud', 'splat', 'mesh'], help='Required with --upload')
    parser.add_argument('--version', type=int, help='Required with --upload — increment per re-reconstruction of the same cell')
    parser.add_argument('--source-coverage-event-ids', help='Required with --upload — comma-separated scan_coverage_events ids that fed this reconstruction')
    parser.add_argument('--admin-token', default=os.environ.get('GCC_ADMIN_TOKEN'), help='Admin bearer token (or set GCC_ADMIN_TOKEN)')
    parser.add_argument('--api-base-url', default=DEFAULT_API_BASE_URL)
    args = parser.parse_args()

    input_path: Path = args.input_path.resolve()
    if not input_path.exists():
        parser.error(f'{input_path} does not exist')

    output_path: Path = (args.output_path or input_path.with_suffix('.glb')).resolve()
    if output_path == input_path:
        parser.error('--out must not overwrite --in')

    print(f'Converting {input_path} -> {output_path} ...')
    try:
        convert_to_glb(input_path, output_path)
    except RuntimeError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1
    print(f'Done. Original preserved at {input_path}.')

    if not args.upload:
        return 0

    missing_upload_args = [
        name for name, value in [
            ('--location-cell-id', args.location_cell_id),
            ('--asset-type', args.asset_type),
            ('--version', args.version),
            ('--source-coverage-event-ids', args.source_coverage_event_ids),
            ('--admin-token', args.admin_token),
        ] if not value
    ]
    if missing_upload_args:
        parser.error(f'--upload requires: {", ".join(missing_upload_args)}')

    source_ids = [int(x) for x in args.source_coverage_event_ids.split(',') if x.strip()]
    r2_key = f'reconstructed/{args.location_cell_id}/{args.version}/{args.asset_type}.glb'

    print(f'Uploading to R2 at {r2_key} ...')
    upload_to_r2(output_path, r2_key)

    print('Registering reconstruction asset ...')
    created = register_reconstruction_asset(
        api_base_url=args.api_base_url,
        admin_token=args.admin_token,
        location_cell_id=args.location_cell_id,
        asset_type=args.asset_type,
        format_='glb',
        r2_key=r2_key,
        version=args.version,
        source_scan_coverage_event_ids=source_ids,
    )
    print(f'Registered as reconstruction_assets.id={created.get("data", {}).get("id")}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
