from indoor_gate_v4.rules import ClassificationObservation as Obs
from indoor_gate_v4.rules import SceneVote, scene_vote_from


def test_window_view_still_votes_indoor():
    vote = scene_vote_from(
        [Obs("living_room", 0.6), Obs("sofa", 0.4), Obs("tree", 0.5)]
    )
    assert vote is SceneVote.INDOOR


def test_street_scene_votes_outdoor():
    vote = scene_vote_from([Obs("street", 0.7), Obs("sidewalk", 0.5), Obs("car", 0.4)])
    assert vote is SceneVote.OUTDOOR


def test_weak_signals_vote_none():
    assert scene_vote_from([Obs("lamp", 0.35), Obs("mug", 0.1)]) is SceneVote.NONE


def test_balanced_porch_votes_outdoor_not_indoor():
    assert scene_vote_from([Obs("ceiling", 0.5), Obs("yard", 0.5)]) is SceneVote.OUTDOOR


def test_sub_confidence_observations_ignored():
    assert scene_vote_from([Obs("kitchen", 0.29), Obs("bedroom", 0.29)]) is SceneVote.NONE


def test_residence_interior_keywords():
    assert scene_vote_from([Obs("bathtub", 0.4), Obs("mirror", 0.3)]) is SceneVote.INDOOR


def test_toilet_keyword_indoor():
    assert scene_vote_from([Obs("toilet", 0.4), Obs("tile", 0.3)]) is SceneVote.INDOOR
