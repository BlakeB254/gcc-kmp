#!/usr/bin/env python3
"""
Runs INSIDE Blender's bundled Python (bpy is only importable that way) —
never invoke this directly, it's a subprocess target of
scripts/convert-scan-asset.py:

    blender --background --python scripts/_blender_convert_worker.py -- \\
        --in <path> --out <path>

Verified against Blender 4.0.2 on the CDX DGX Spark: `wm.ply_import` and
`wm.obj_import` exist and work. This Blender build has NO USD add-on
registered (`bpy.ops.wm.usd_import` doesn't exist, and `addon_utils.modules()`
lists nothing USD-related) — .usdz/.usdc/.usda inputs are rejected with a
clear error rather than silently failing partway through.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import bpy

SUPPORTED_IMPORT_EXTENSIONS = {'.ply', '.obj'}


def parse_args() -> argparse.Namespace:
    # Blender passes its own args through; ours come after a bare `--`.
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument('--in', dest='input_path', required=True)
    parser.add_argument('--out', dest='output_path', required=True)
    return parser.parse_args(argv)


def main() -> int:
    args = parse_args()
    input_path = Path(args.input_path).resolve()
    output_path = Path(args.output_path).resolve()
    ext = input_path.suffix.lower()

    if ext in {'.usdz', '.usdc', '.usda'}:
        print(
            f'ERROR: {ext} input given, but this Blender install has no USD '
            'add-on registered (bpy.ops.wm.usd_import does not exist). '
            'Convert USDZ to OBJ/PLY with a USD-capable toolchain first, or '
            'install a Blender build with USD support.',
            file=sys.stderr,
        )
        return 2
    if ext not in SUPPORTED_IMPORT_EXTENSIONS:
        print(f'ERROR: unsupported input extension {ext!r} (supported: {SUPPORTED_IMPORT_EXTENSIONS})', file=sys.stderr)
        return 2

    # Start from Blender's factory-default empty scene, not the startup file's
    # default cube/light/camera — those would end up exported into the GLB.
    bpy.ops.wm.read_factory_settings(use_empty=True)

    try:
        if ext == '.ply':
            bpy.ops.wm.ply_import(filepath=str(input_path))
        elif ext == '.obj':
            bpy.ops.wm.obj_import(filepath=str(input_path))
    except Exception as exc:  # noqa: BLE001 — surface any importer failure as a clear worker error
        print(f'ERROR: import failed: {exc}', file=sys.stderr)
        return 1

    if len(bpy.context.scene.objects) == 0:
        print('ERROR: import produced no scene objects — input file may be empty or malformed', file=sys.stderr)
        return 1

    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        bpy.ops.export_scene.gltf(filepath=str(output_path), export_format='GLB')
    except Exception as exc:  # noqa: BLE001
        print(f'ERROR: glTF export failed: {exc}', file=sys.stderr)
        return 1

    if not output_path.exists():
        print('ERROR: export reported success but output file is missing', file=sys.stderr)
        return 1

    print(f'OK: wrote {output_path}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
