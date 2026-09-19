import json

import httpx
import pytest
from langchain_openai import ChatOpenAI

from paperagent_engine.contracts import EngineError
from paperagent_engine.models import LangChainModel
from paperagent_engine.tools import ToolSet


def adapter(message):
    requests = []

    def handle(request):
        requests.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "id": "chatcmpl-synthetic",
                "object": "chat.completion",
                "created": 0,
                "model": "synthetic",
                "choices": [
                    {
                        "index": 0,
                        "message": message,
                        "finish_reason": "tool_calls" if message.get("tool_calls") else "stop",
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
            },
        )

    engine = LangChainModel.__new__(LangChainModel)
    engine.chat = ChatOpenAI(
        model="synthetic",
        api_key="synthetic-not-a-key",
        max_retries=0,
        http_client=httpx.Client(transport=httpx.MockTransport(handle)),
    )
    return engine, requests


def test_planner_uses_langchain_structured_output():
    payload = {
        "steps": [
            {
                "key": "inspect",
                "objective": "Read documents",
                "done_when": "Evidence available",
                "depends_on": [],
            }
        ]
    }
    model, requests = adapter({"role": "assistant", "content": json.dumps(payload)})
    assert model.plan({"frame": {"objective": "analyze"}}).model_dump() == payload
    assert requests[0]["response_format"]["type"] == "json_schema"


def test_execution_uses_native_langchain_tool_calls(submission):
    model, requests = adapter(
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [
                {
                    "id": "call-demo",
                    "type": "function",
                    "function": {"name": "read_document", "arguments": '{"document_id":"study"}'},
                }
            ],
        }
    )
    action = model.act({"step": {"key": "inspect"}}, ToolSet(submission, []).tools)
    assert action.call.name == "read_document"
    assert action.call.arguments == {"document_id": "study"}
    assert requests[0]["parallel_tool_calls"] is False
    assert len(requests[0]["tools"]) == 5


def test_execution_rejects_unstructured_empty_response(submission):
    model, _ = adapter({"role": "assistant", "content": "trust me"})
    with pytest.raises(EngineError, match="MODEL_RESPONSE_INVALID"):
        model.act({}, ToolSet(submission, []).tools)


def test_step_finish_is_validated(submission):
    model, _ = adapter(
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [
                {
                    "id": "finish",
                    "type": "function",
                    "function": {
                        "name": "finish_step",
                        "arguments": json.dumps(
                            {
                                "kind": "complete",
                                "summary": "Read complete",
                                "evidence_refs": ["evidence_1_0_1"],
                            }
                        ),
                    },
                }
            ],
        }
    )
    assert model.act({}, ToolSet(submission, []).tools).result.evidence_refs == ["evidence_1_0_1"]


def test_synthesis_uses_model_response():
    model, _ = adapter({"role": "assistant", "content": "Synthetic evidence-backed report"})
    assert model.synthesize({}) == "Synthetic evidence-backed report"
