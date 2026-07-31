# Capture confidence artifact contract

Version: 1

The iOS reconstruction recorder writes `chunk-NNN.confidence` beside the
legacy `chunk-NNN.depth`. It selects both planes from the same
`ARDepthData` at the existing 2 Hz depth cadence, so their `t` values use the
same ARKit timestamp. The depth format is unchanged. When ARKit supplies no
confidence map (including non-LiDAR capture), the empty confidence file is
deleted and no `confidence` packet is emitted.

Each confidence file is a concatenation of independently length-prefixed
records. All integer fields and the IEEE-754 `Float64` timestamp bit pattern
are little-endian. There is no alignment padding between fields or records.

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | `UInt32` | `recordLength`: bytes after this field (`32 + payloadLength`) |
| 4 | 2 | `UInt16` | `version`: `1` |
| 6 | 2 | `UInt16` | `fixedHeaderLength`: `32`, measured from `version` through `payloadLength` |
| 8 | 8 | `Float64` | `t`: ARKit frame timestamp in seconds |
| 16 | 4 | `UInt32` | pixel width |
| 20 | 4 | `UInt32` | pixel height |
| 24 | 4 | `UInt32` | bytes per row |
| 28 | 4 | `UInt32` | Core Video pixel-format FourCC |
| 32 | 4 | `UInt32` | `payloadLength`: `height * bytesPerRow` |
| 36 | variable | bytes | raw confidence plane, row padding preserved |

Readers must skip unknown versions using `recordLength`. Version 1 does not
compress or reinterpret ARKit confidence values.

The finished-chunk packet kind is `confidence`. Its upload extension is
`.confidence` and its content type is
`application/vnd.gcc.depth-confidence-v1`.

Pose JSONL frame records add these keys without changing header schema 2:
`exposureDurationSeconds`, `iso`, `whiteBalanceRedGain`,
`whiteBalanceGreenGain`, `whiteBalanceBlueGain`, and `lensPosition`. Each
value is a JSON number when the sensor exposes a finite value, otherwise
`null`. Existing pose, intrinsics, tracking, thermal, video, and depth fields
retain their prior meanings and encodings.
