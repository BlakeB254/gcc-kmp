# Indoor Gate v4 classifier

Server-side replica of the shipped GCC OutdoorGate v4 policy
(`composeApp/.../OutdoorGate.kt` + `TrashDetectionRules.kt`).

The on-device gate remains the capture authority. This slice lets
reconstruction and audit tools classify the same label packets without
reimplementing thresholds.

## What it classifies

Vision / ML Kit **label packets** plus optional GPS accuracy, ceiling-plane,
and map-containment evidence. It does **not** ingest pixels, generate imagery,
or persist frames.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/healthz` | liveness |
| GET | `/v1/indoor-gate/model` | model card |
| POST | `/v1/indoor-gate/classify` | classify one scan packet |

`PORT` defaults to `8088`. Bind address comes from the process environment.

## Tests

From this directory:

```
python3 -m pytest tests
```

Acceptance: indoor residence packets flip INDOOR and block capture; outdoor
civic packets unlock within 5 seconds of evidence; validation-set accuracy
is at least 80 percent.

## Why no SQL migration

Policy is stateless. Gate events already live on the scan manifest. A
classification table would duplicate client evidence without changing
behavior, and GCC schema ownership stays in Drizzle (`site/src/db/schema.ts`).
