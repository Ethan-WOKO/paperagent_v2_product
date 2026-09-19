import pytest
from pydantic import ValidationError

from paperagent_engine.contracts import Plan, Submission
from paperagent_engine.tools import ToolSet


@pytest.mark.parametrize(
    "steps",
    [
        [],
        [{"key": "a", "objective": "x", "done_when": "y", "depends_on": ["a"]}],
        [{"key": "a", "objective": "x", "done_when": "y", "depends_on": ["missing"]}],
        [{"key": "a", "objective": "x", "done_when": "y"}] * 2,
    ],
)
def test_invalid_plans_rejected(steps):
    with pytest.raises(ValidationError):
        Plan.model_validate({"steps": steps})


@pytest.mark.parametrize("field,value", [("permission_tier", "WRITE"), ("run_mode", "DIRECT")])
def test_demo_cannot_claim_other_authority(submission, field, value):
    data = submission.model_dump()
    data["frame"][field] = value
    with pytest.raises(ValidationError):
        Submission.model_validate(data)


@pytest.mark.parametrize(
    "name,args,error",
    [
        ("workspace.write", {}, "TOOL_NOT_ALLOWED"),
        ("read_document", {"document_id": "../secret"}, "TOOL_ARGUMENTS_INVALID"),
        ("read_document", {"document_id": "unknown"}, "TOOL_ARGUMENTS_INVALID"),
        ("read_document", {"document_id": "study", "admin": True}, "TOOL_ARGUMENTS_INVALID"),
    ],
)
def test_tools_cannot_escape_submitted_data(submission, name, args, error):
    result = ToolSet(submission, []).invoke(name, args)
    assert result == {"success": False, "error": error, "output": None}


def test_document_pagination_is_bounded(submission):
    data = submission.model_dump()
    data["documents"] = [{"id": "long", "text": "a" * 4100}]
    tools = ToolSet(Submission.model_validate(data), [])
    first = tools.invoke("read_document", {"document_id": "long"})["output"]
    assert len(first["text"]) == 2000
    assert first["next_offset"] == 2000
    last = tools.invoke("read_document", {"document_id": "long", "offset": 4000})["output"]
    assert len(last["text"]) == 100
    assert last["next_offset"] is None


def test_memory_is_explicitly_not_evidence(submission):
    tools = ToolSet(submission, [{"id": "m", "content": "Chinese response"}])
    assert tools.invoke("search_memory", {"query": "Chinese"})["output"]["not_evidence"] is True


def test_duplicate_document_ids_rejected(submission):
    data = submission.model_dump()
    data["documents"] = [data["documents"][0]] * 2
    with pytest.raises(ValidationError):
        Submission.model_validate(data)
