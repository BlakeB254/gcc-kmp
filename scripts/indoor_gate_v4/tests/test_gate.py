from indoor_gate_v4.gate import OutdoorGate, OutdoorState
from indoor_gate_v4.rules import SceneVote

T0 = 1_000_000


def test_starts_uncertain():
    assert OutdoorGate().state is OutdoorState.UNCERTAIN


def test_null_accuracy_stays_uncertain():
    gate = OutdoorGate()
    for i in range(11):
        gate.on_accuracy_sample(None, T0 + i * 1000)
    assert gate.state is OutdoorState.UNCERTAIN


def test_single_bad_accuracy_does_not_flip():
    gate = OutdoorGate()
    gate.on_accuracy_sample(40.0, T0)
    assert gate.state is OutdoorState.UNCERTAIN


def test_sustained_bad_accuracy_goes_indoor():
    gate = OutdoorGate()
    for i in range(7):
        gate.on_accuracy_sample(40.0, T0 + i * 1000)
    assert gate.state is OutdoorState.INDOOR


def test_sustained_good_accuracy_goes_outdoor():
    gate = OutdoorGate()
    for i in range(7):
        gate.on_accuracy_sample(5.0, T0 + i * 1000)
    assert gate.state is OutdoorState.OUTDOOR
    assert gate.capture_allowed is True


def test_recovers_after_sustained_good_accuracy():
    gate = OutdoorGate()
    for i in range(7):
        gate.on_accuracy_sample(40.0, T0 + i * 1000)
    assert gate.state is OutdoorState.INDOOR
    t1 = T0 + 20_000
    for i in range(7):
        gate.on_accuracy_sample(5.0, t1 + i * 1000)
    assert gate.state is OutdoorState.OUTDOOR


def test_mixed_recovery_stays_indoor():
    gate = OutdoorGate()
    for i in range(7):
        gate.on_accuracy_sample(40.0, T0 + i * 1000)
    t1 = T0 + 7000
    for i in range(7):
        gate.on_accuracy_sample(5.0 if i % 2 == 0 else 40.0, t1 + i * 1000)
    assert gate.state is OutdoorState.INDOOR


def test_scene_plus_bad_accuracy_flips_immediately():
    gate = OutdoorGate()
    gate.on_accuracy_sample(40.0, T0)
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 500)
    assert gate.state is OutdoorState.INDOOR


def test_single_scene_vote_does_not_flip():
    gate = OutdoorGate()
    gate.on_scene_vote(SceneVote.INDOOR, T0)
    assert gate.state is OutdoorState.UNCERTAIN


def test_two_camera_indoor_votes_flip():
    gate = OutdoorGate()
    gate.on_scene_vote(SceneVote.INDOOR, T0)
    assert gate.state is OutdoorState.UNCERTAIN
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 2000)
    assert gate.state is OutdoorState.INDOOR


def test_outdoor_vote_resets_indoor_streak():
    gate = OutdoorGate()
    gate.on_scene_vote(SceneVote.INDOOR, T0)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 1000)
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 2000)
    assert gate.state is OutdoorState.UNCERTAIN


def test_map_containment_flips_immediately():
    gate = OutdoorGate()
    gate.on_map_evidence(True, T0)
    assert gate.state is OutdoorState.INDOOR


def test_leaving_building_allows_recovery():
    gate = OutdoorGate()
    gate.on_map_evidence(True, T0)
    gate.on_map_evidence(True, T0 + 1000)
    t1 = T0 + 20_000
    gate.on_map_evidence(False, t1)
    for i in range(7):
        gate.on_accuracy_sample(5.0, t1 + i * 1000)
    assert gate.state is OutdoorState.OUTDOOR


def test_capture_disallowed_while_uncertain():
    assert OutdoorGate().capture_allowed is False


def test_ceiling_plane_flips_indoor_instantly():
    gate = OutdoorGate()
    gate.on_ceiling_plane(True, T0)
    assert gate.state is OutdoorState.INDOOR
    assert gate.capture_allowed is False


def test_scene_streak_flips_despite_good_gps():
    gate = OutdoorGate()
    gate.on_accuracy_sample(5.0, T0)
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 500)
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 1500)
    assert gate.state is OutdoorState.INDOOR


def test_two_outdoor_votes_recover_fast():
    gate = OutdoorGate()
    gate.on_scene_vote(SceneVote.INDOOR, T0)
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 1000)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 3000)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 4000)
    assert gate.state is OutdoorState.OUTDOOR


def test_recovery_blocked_by_recent_ceiling():
    gate = OutdoorGate()
    gate.on_ceiling_plane(True, T0)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 1000)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 2000)
    assert gate.state is OutdoorState.INDOOR
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 6000)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 7000)
    assert gate.state is OutdoorState.OUTDOOR


def test_recovery_blocked_while_map_contained():
    gate = OutdoorGate()
    gate.on_map_evidence(True, T0)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 1000)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 2000)
    assert gate.state is OutdoorState.INDOOR


def test_override_survives_weak_evidence():
    gate = OutdoorGate()
    gate.force_outdoors()
    gate.on_scene_vote(SceneVote.INDOOR, T0)
    gate.on_scene_vote(SceneVote.INDOOR, T0 + 1000)
    for i in range(7):
        gate.on_accuracy_sample(40.0, T0 + 2000 + i * 1000)
    assert gate.state is OutdoorState.OUTDOOR


def test_override_survives_map_containment():
    gate = OutdoorGate()
    gate.force_outdoors()
    gate.on_map_evidence(True, T0)
    assert gate.state is OutdoorState.OUTDOOR


def test_override_yields_to_ceiling():
    gate = OutdoorGate()
    gate.force_outdoors()
    gate.on_ceiling_plane(True, T0)
    assert gate.state is OutdoorState.INDOOR


def test_last_indoor_flip_timestamp():
    gate = OutdoorGate()
    assert gate.last_indoor_flip_at_ms is None
    gate.on_ceiling_plane(True, T0)
    assert gate.last_indoor_flip_at_ms == T0


def test_stale_outdoor_streak_does_not_shortcut_recovery():
    gate = OutdoorGate()
    gate.on_scene_vote(SceneVote.OUTDOOR, T0)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 1000)
    gate.on_ceiling_plane(True, T0 + 2000)
    assert gate.state is OutdoorState.INDOOR
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 8000)
    assert gate.state is OutdoorState.INDOOR
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 9000)
    assert gate.state is OutdoorState.OUTDOOR


def test_two_outdoor_votes_unlock_from_uncertain():
    gate = OutdoorGate()
    gate.on_scene_vote(SceneVote.OUTDOOR, T0)
    gate.on_scene_vote(SceneVote.OUTDOOR, T0 + 750)
    assert gate.state is OutdoorState.OUTDOOR
    assert gate.capture_allowed is True
