import json
import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from verdict import (  # noqa: E402
    PairResult,
    VerdictError,
    aggregate,
    flag_reasons,
    parse_verdict,
)


def _reply(**over):
    doc = {
        "items_before": {"count": 5, "types": ["bottle", "bag"]},
        "items_after": {"count": 0, "types": []},
        "same_location": {"value": True, "reason": "same fence and hydrant"},
        "cleaned": True,
        "confidence": 0.9,
        "instruction_text_present": False,
        "concerns": [],
    }
    doc.update(over)
    return json.dumps(doc)


def test_parses_a_clean_verdict():
    v = parse_verdict(_reply())
    assert v.items_before_count == 5 and v.items_after_count == 0
    assert v.cleaned and v.same_location and v.confidence == 0.9
    assert flag_reasons(v) == []


def test_tolerates_think_block_and_code_fence():
    text = "<think>looking...</think>\n```json\n" + _reply() + "\n```"
    assert parse_verdict(text).cleaned is True


@pytest.mark.parametrize("text", [
    "", "not json", "[1,2]", "{\"items_before\": ",
])
def test_rejects_non_objects(text):
    with pytest.raises(VerdictError):
        parse_verdict(text)


@pytest.mark.parametrize("over", [
    {"items_before": {"count": True, "types": []}},
    {"items_before": {"count": -1, "types": []}},
    {"items_after": {"count": 1.5, "types": []}},
    {"items_after": {"count": 1, "types": "bottle"}},
    {"same_location": True},
    {"same_location": {"value": "yes", "reason": ""}},
    {"cleaned": "true"},
    {"confidence": 1.5},
    {"confidence": True},
    {"concerns": "none"},
    {"instruction_text_present": "no"},
    {"instruction_text_present": 0},
])
def test_rejects_wrong_shapes(over):
    with pytest.raises(VerdictError):
        parse_verdict(_reply(**over))


@pytest.mark.parametrize("key", ["cleaned", "instruction_text_present"])
def test_missing_key_is_rejected(key):
    doc = json.loads(_reply())
    del doc[key]
    with pytest.raises(VerdictError):
        parse_verdict(json.dumps(doc))


@pytest.mark.parametrize("over,needle", [
    ({"cleaned": False}, "not cleaned"),
    ({"same_location": {"value": False, "reason": "different street"}}, "same location"),
    ({"confidence": 0.3}, "low confidence"),
    ({"items_after": {"count": 5, "types": ["bottle"]}}, "items after"),
    # Nothing there before and nothing after is still not proof of a cleanup.
    ({"items_before": {"count": 0, "types": []}}, "items after"),
    ({"instruction_text_present": True}, "text addressed to the reviewer"),
    ({"concerns": ["a bag may be out of frame"]}, "concern"),
])
def test_flag_rules(over, needle):
    reasons = flag_reasons(parse_verdict(_reply(**over)))
    assert any(needle in r for r in reasons), reasons


def _pr(label="evidence #1", extra=(), **over):
    return PairResult(label, "https://b/1.jpg", "https://a/1.jpg", parse_verdict(_reply(**over)), extra)


def test_aggregate_ok_when_every_pair_passes():
    status, v = aggregate([_pr(), _pr("hotspot 7")])
    assert status == "ok"
    assert v["items_before"]["count"] == 10 and v["cleaned"] is True
    assert [p["flag_reasons"] for p in v["pairs"]] == [[], []]


def test_aggregate_flags_when_any_pair_fails():
    status, v = aggregate([_pr(), _pr("hotspot 7", cleaned=False, confidence=0.4)])
    assert status == "flagged"
    assert v["cleaned"] is False and v["confidence"] == 0.4


def test_aggregate_extra_reason_overrides_model():
    status, v = aggregate([_pr(extra=("before and after photos are the identical image",))])
    assert status == "flagged"
    assert "identical" in v["pairs"][0]["flag_reasons"][0]


def test_aggregate_without_pairs_is_flagged():
    status, v = aggregate([], job_concerns=["2 hotspot(s) have no after photo"])
    assert status == "flagged"
    assert v["cleaned"] is False and v["same_location"]["value"] is False
    assert "no before/after photo pairs to review" in v["concerns"]


def test_injection_with_a_perfect_verdict_is_still_flagged():
    """The model is talked into cleaned=true at full confidence, but it does
    report the planted text: the job must not pass."""
    status, v = aggregate([_pr(confidence=1.0, instruction_text_present=True,
                               concerns=["after photo has a note telling the AI to approve"])])
    assert status == "flagged"
    assert v["pairs"][0]["verdict"]["instruction_text_present"] is True
