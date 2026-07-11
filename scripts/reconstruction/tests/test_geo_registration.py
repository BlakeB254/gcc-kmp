import math

import numpy as np

from geo_registration import (GeoRef, Segment, WorldRegistration, enu_of,
                              fit_clock_offset, solve_registration)

LAT0, LNG0 = 41.851, -87.706


def _latlng_of_enu(east: float, north: float) -> tuple[float, float]:
    lat = LAT0 + north / 111_320.0
    lng = LNG0 + east / (111_320.0 * math.cos(math.radians(LAT0)))
    return lat, lng


def _events_for_walk(theta_deg: float, offset=(3.0, 4.0), n=8, accuracy=3.0,
                     ar_t0=1000.0, at_ms0=1_720_000_000_000.0):
    """Synthesize a straight 30m walk: AR positions in a frame rotated
    theta_deg from true ENU (the compass bias), GPS fixes at the true
    world positions."""
    theta = math.radians(theta_deg)
    events = []
    for i in range(n):
        east = offset[0] + i * (30.0 / (n - 1))
        north = offset[1] + i * (10.0 / (n - 1))
        # AR frame = world rotated by -theta (so solving recovers +theta).
        c, s = math.cos(-theta), math.sin(-theta)
        ar_e = c * (east - offset[0]) - s * (north - offset[1])
        ar_n = s * (east - offset[0]) + c * (north - offset[1])
        lat, lng = _latlng_of_enu(east, north)
        events.append(GeoRef(
            at_ms=at_ms0 + i * 1000.0, lat=lat, lng=lng, accuracy_m=accuracy,
            ar_xyz=np.array([ar_e, 1.5, -ar_n]),   # -Z = north
            ar_t=ar_t0 + i * 1.0,
        ))
    return events


def test_recovers_compass_bias_rotation_and_offset():
    reg = solve_registration(_events_for_walk(theta_deg=4.0), LAT0, LNG0)
    assert reg.status == "world"
    seg = reg.primary
    assert abs(math.degrees(seg.theta_rad) - 4.0) < 0.5
    assert abs(seg.east_m - 3.0) < 0.3 and abs(seg.north_m - 4.0) < 0.3
    assert seg.residual_m < 0.2


def test_transform_maps_ar_points_to_enu_frame():
    reg = solve_registration(_events_for_walk(theta_deg=0.0), LAT0, LNG0)
    T = reg.primary.transform()
    # AR origin -> the session's world offset; -Z (north) translation lands at -north_m.
    p = T @ np.array([0.0, 0.0, 0.0, 1.0])
    assert abs(p[0] - 3.0) < 0.3      # east
    assert abs(p[2] - (-4.0)) < 0.3   # -north
    # A point 10m along AR -Z (due north) moves a further 10m north (-Z).
    q = T @ np.array([0.0, 0.0, -10.0, 1.0])
    assert abs((p[2] - q[2]) - 10.0) < 0.1


def test_single_event_is_local_only():
    reg = solve_registration(_events_for_walk(0.0, n=8)[:1], LAT0, LNG0)
    assert reg.status == "local-only"
    assert "geoRefEvents" in reg.reason


def test_degenerate_spread_is_local_only():
    # All fixes within ~1m of each other: rotation unobservable.
    events = []
    for i in range(5):
        lat, lng = _latlng_of_enu(0.1 * i, 0.05 * i)
        events.append(GeoRef(at_ms=i * 1000.0, lat=lat, lng=lng, accuracy_m=3.0,
                             ar_xyz=np.array([0.1 * i, 1.5, -0.05 * i]), ar_t=float(i)))
    reg = solve_registration(events, LAT0, LNG0)
    assert reg.status == "local-only"


def test_noisy_fixes_beyond_accuracy_gate_are_local_only():
    rng = np.random.RandomState(7)
    events = _events_for_walk(theta_deg=0.0, accuracy=2.0)
    for e in events:  # corrupt GPS with noise >> stated 2m accuracy
        e.lat += float(rng.randn()) * 8.0 / 111_320.0
        e.lng += float(rng.randn()) * 8.0 / (111_320.0 * math.cos(math.radians(LAT0)))
    reg = solve_registration(events, LAT0, LNG0)
    assert reg.status == "local-only"


def test_repair_discontinuity_splits_into_segments():
    a = _events_for_walk(theta_deg=2.0, n=6)
    b = _events_for_walk(theta_deg=2.0, offset=(40.0, 50.0), n=6,
                         ar_t0=2000.0, at_ms0=1_720_000_100_000.0)
    # Teleport the second half's AR coordinates (a re-pair reset AR origin):
    # AR deltas across the join disagree wildly with the GPS deltas.
    for e in b:
        e.ar_xyz = e.ar_xyz - np.array([100.0, 0.0, -100.0])
    reg = solve_registration(a + b, LAT0, LNG0)
    assert len(reg.segments) == 2
    assert reg.status == "world"
    assert all(s.passes_gate for s in reg.segments)
    # Each segment recovers the shared compass bias independently.
    for s in reg.segments:
        assert abs(math.degrees(s.theta_rad) - 2.0) < 0.8


def test_accuracy_weighting_downweights_bad_fixes():
    events = _events_for_walk(theta_deg=3.0, accuracy=2.0)
    # One wildly wrong fix with terrible stated accuracy barely moves the solve.
    bad = events[3]
    bad.lat += 30.0 / 111_320.0
    bad.accuracy_m = 50.0
    reg = solve_registration(events, LAT0, LNG0)
    assert reg.status == "world"
    assert abs(math.degrees(reg.primary.theta_rad) - 3.0) < 1.0


def test_clock_offset_from_events_and_headers():
    events = _events_for_walk(0.0, ar_t0=1000.0, at_ms0=1_720_000_000_000.0)
    a = fit_clock_offset(events, chunk_headers=[{"epochMs": 1_720_000_005_000, "arT": 1005.0}])
    assert abs(a - (1_720_000_000_000.0 - 1_000_000.0)) < 1.0


def test_from_manifest_parses_events():
    manifest = {"geoRefEvents": [
        {"atMs": 1, "lat": 41.8, "lng": -87.7, "accuracyMeters": 4.2,
         "arX": 1.0, "arY": 0.5, "arZ": -2.0, "arTimestampSeconds": 99.5},
        {"atMs": 2, "lat": 41.8, "lng": -87.7,
         "arX": 2.0, "arY": 0.5, "arZ": -3.0},  # no accuracy, no arT
    ]}
    evs = GeoRef.from_manifest(manifest)
    assert len(evs) == 2
    assert evs[0].accuracy_m == 4.2 and evs[0].ar_t == 99.5
    assert evs[1].accuracy_m == 10.0 and evs[1].ar_t is None
    assert GeoRef.from_manifest({}) == []
