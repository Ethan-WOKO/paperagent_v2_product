import copy
import json
import threading
import time
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from paperagent_engine.product import create_product_app
from paperagent_engine.product_gateway import ProductGateway
from paperagent_engine.storage import digest

TASK = "task." + "a" * 64
TOKEN = "python-product-test-token-32-chars-long"
AUTH = {"Authorization": "Bearer " + TOKEN}


def submission():
    authority = {
        "runMode": "PERSISTENT_PLAN_EXECUTE",
        "sessionRef": "session.1",
        "project": {"projectId": "1", "projectVersion": "b" * 64},
        "instruction": "Analyze the paper",
        "permissions": {"readProject": True, "writeWorkspace": False, "executeSandbox": False},
        "model": {"provider": "test", "model": "test-model", "fallbacks": []},
    }
    return {
        "contractVersion": "1.0",
        "taskId": TASK,
        "requestDigest": digest(authority),
        "authority": authority,
        "gateway": {
            "taskGrant": "ephemeral-grant-for-test-32-characters",
            "expiresAt": "2099-01-01T00:00:00Z",
        },
    }


class JavaGateway:
    def __init__(self):
        self.checkpoint = None
        self.revision = 0
        self.events = []
        self.calls = []
        self.models = 0
        self.cancelled = False
        self.fail_checkpoint_once = False
        self.lock = threading.RLock()

    def handle(self, request):
        with self.lock:
            return self._handle(request)

    def _handle(self, request):
        path = request.url.path
        body = json.loads(request.content) if request.content else None
        self.calls.append((path, body))
        result = {}
        if path.endswith("/checkpoint"):
            if not self.checkpoint:
                return httpx.Response(404, json={})
            result = {"checkpointRevision": self.revision, "checkpoint": self.checkpoint}
        elif path.endswith("/checkpoints"):
            if self.fail_checkpoint_once and self.models:
                self.fail_checkpoint_once = False
                raise httpx.ReadError("synthetic persistence outage")
            assert body["expectedRevision"] == (self.revision or None)
            assert "taskGrant" not in json.dumps(body["checkpoint"])
            self.checkpoint = copy.deepcopy(body["checkpoint"])
            self.revision += 1
            result = {"checkpointRevision": self.revision}
        elif path.endswith("/claim"):
            result = {
                "task": {
                    "lease": {"owner": body["owner"], "token": "lease", "fence": 1},
                    "taskGrant": "ephemeral-grant-for-test-32-characters",
                    "cancellationRequested": self.cancelled,
                }
            }
        elif path.endswith("/cancel-request"):
            self.cancelled = True
        elif path.endswith("/lease/renew"):
            result = {"cancellationRequested": self.cancelled}
        elif path.endswith("/lease/release"):
            pass
        elif path.endswith("/events"):
            if body:
                assert body["event"]["sequence"] == len(self.events) + 1
                self.events.append(body["event"])
            result = {"events": self.events}
        elif path.endswith("/workspace/files"):
            result = {
                "taskId": TASK,
                "projectVersion": "b" * 64,
                "files": [
                    {
                        "path": "paper.txt",
                        "sha256": "c" * 64,
                        "sizeBytes": 20,
                        "mediaType": "text/plain",
                    }
                ],
            }
        elif path.endswith("/workspace/read"):
            assert (
                request.headers["Authorization"] == "Bearer ephemeral-grant-for-test-32-characters"
            )
            result = {
                "path": "paper.txt",
                "sha256": "c" * 64,
                "content": "Sample size is 100.",
                "truncated": False,
            }
        elif path.endswith("/model-completions"):
            self.models += 1
            assert (
                request.headers["Authorization"] == "Bearer ephemeral-grant-for-test-32-characters"
            )
            assert body["requestDigest"] == digest(
                {k: v for k, v in body.items() if k != "requestDigest"}
            )
            assert all(t["function"]["name"] not in {"Plan", "finish_step"} for t in body["tools"])
            policy = body["messages"][0]["content"]
            assert "Do not continue or summarize a previous task" in policy
            assert "greetings" in policy
            current = next(
                m["content"]
                for m in body["messages"]
                if m["role"] == "user" and m["content"].startswith("Current task: ")
            )
            observations = [
                json.loads(m["content"]) for m in body["messages"] if m["role"] == "tool"
            ]
            native_ids = {c["id"] for m in body["messages"] for c in m.get("toolCalls", [])}
            assert all(
                m["toolCallId"] in native_ids for m in body["messages"] if m["role"] == "tool"
            )
            call = None
            direct = {
                "Current task: 你好": "你好！有什么可以帮你？",
                "Current task: 你好，你是什么模型？": "provider=test; model=test-model",
                "Current task: 什么是快速排序？": "快速排序通过分区递归排列元素。",
            }
            greeting = current in direct
            if not greeting:
                if not observations:
                    call = ("list_project_files", {})
                elif len(observations) == 1:
                    file = observations[-1]["output"]["files"][0]
                    assert len(file["sha256"]) == 64
                    call = (
                        "read_project_file",
                        {"path": file["path"], "expectedSha256": file["sha256"]},
                    )
            result = {
                "contractVersion": "1.0",
                "clientRequestId": body["clientRequestId"],
                "requestDigest": body["requestDigest"],
                "content": None
                if call
                else (direct[current] if greeting else "paper.txt reports a sample of 100."),
                "toolCalls": [
                    {
                        "id": f"model-call-{self.models}",
                        "name": call[0],
                        "arguments": json.dumps(call[1]),
                    }
                ]
                if call
                else [],
                "usage": {"promptTokens": 10, "completionTokens": 10},
            }
        else:
            raise AssertionError("Unexpected gateway path: " + path)
        return httpx.Response(200, json=copy.deepcopy(result))


def make_app(tmp_path, java):
    gateway = ProductGateway(
        "http://127.0.0.1:8080",
        "java-service-token",
        httpx.Client(transport=httpx.MockTransport(java.handle)),
    )
    return create_product_app(tmp_path, TOKEN, gateway)


def finish(client):
    for _ in range(200):
        view = client.get(f"/v1/tasks/{TASK}", headers=AUTH).json()
        if view["state"] in {"succeeded", "failed", "cancelled"}:
            return view
        time.sleep(0.01)
    raise AssertionError("Product engine did not finish")


def test_real_graph_uses_product_models_tools_and_durable_events(tmp_path):
    java = JavaGateway()
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.post("/v1/tasks", json=submission(), headers=AUTH).status_code == 202
        view = finish(client)
        assert view["state"] == "succeeded", view
        assert java.models == 3
        assert java.checkpoint["modelCalls"] == 3
        assert "promptTokens" not in java.checkpoint["metrics"]
        assert any(path.endswith("/workspace/read") for path, _ in java.calls)
        assert not any(
            "sandbox" in path or "publish" in path or "workspace/write" in path
            for path, _ in java.calls
        )
        sse = client.get(f"/v1/tasks/{TASK}/events", headers={**AUTH, "Last-Event-ID": "1"})
        assert "paper.txt reports a sample of 100." in sse.text
        assert "ephemeral-grant-for-test-32-characters" not in sse.text
        assert client.post("/v1/tasks", json=submission(), headers=AUTH).json()["replayed"]
        assert java.models == 3
        schemas = [
            json.loads(p.read_text())
            for p in (Path(__file__).resolve().parents[2] / "agent-engine-contract/schemas").glob(
                "*.json"
            )
        ]
        registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas)
        for name, values in [
            ("task-submission", [submission()]),
            ("task-view", [view]),
            ("task-event", java.events),
        ]:
            schema = next(s for s in schemas if s["$id"].endswith(name + ".schema.json"))
            validator = Draft202012Validator(
                schema, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
            )
            for value in values:
                validator.validate(value)
        assert [e["state"] for e in java.events if e["type"] == "tool"] == [
            "requested",
            "succeeded",
        ] * 2
    # Restart does not claim anything; terminal reads use durable product projections.
    claims = sum(path.endswith("/claim") for path, _ in java.calls)
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.get(f"/v1/tasks/{TASK}", headers=AUTH).json()["state"] == "succeeded"
        assert sum(path.endswith("/claim") for path, _ in java.calls) == claims
        db = client.app.state.service.runtime.store.connection
        stored = str(db.execute("SELECT submission FROM product_bindings").fetchall())
        assert "ephemeral-grant-for-test-32-characters" not in stored


@pytest.mark.parametrize("permission", ["writeWorkspace", "executeSandbox"])
def test_python_admission_rejects_mutation_authority(tmp_path, permission):
    java = JavaGateway()
    data = submission()
    data["authority"]["permissions"][permission] = True
    data["requestDigest"] = digest(data["authority"])
    with TestClient(make_app(tmp_path, java)) as client:
        response = client.post("/v1/tasks", json=data, headers=AUTH)
        assert response.status_code == 403
        assert java.calls == []


def test_product_requires_auth_and_rejects_bad_digest(tmp_path):
    java = JavaGateway()
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.post("/v1/tasks", json=submission()).status_code == 401
        data = submission()
        data["requestDigest"] = "0" * 64
        assert client.post("/v1/tasks", json=data, headers=AUTH).status_code == 400
        assert java.calls == []


def test_requested_cancellation_finishes_without_model_calls(tmp_path):
    java = JavaGateway()
    java.cancelled = True
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.post("/v1/tasks", json=submission(), headers=AUTH).status_code == 202
        assert finish(client)["state"] == "cancelled"
        assert java.models == 0
        assert not any(e["type"] == "delivery" for e in java.events)


def test_explicit_resubmit_reconciles_paused_checkpoint_without_repeating_model(tmp_path):
    java = JavaGateway()
    java.fail_checkpoint_once = True
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.post("/v1/tasks", json=submission(), headers=AUTH).status_code == 202
        for _ in range(200):
            if not client.app.state.service.pending:
                break
            time.sleep(0.01)
        assert not client.app.state.service.pending
        assert java.models == 1
        assert client.get(f"/v1/tasks/{TASK}", headers=AUTH).json()["state"] == "running"
        assert client.post("/v1/tasks", json=submission(), headers=AUTH).json()["replayed"]
        assert finish(client)["state"] == "succeeded"
        assert java.models == 3


def test_large_and_invalid_requests_do_not_echo_input(tmp_path):
    with TestClient(make_app(tmp_path, JavaGateway())) as client:
        response = client.post("/v1/tasks", json="private-input", headers=AUTH)
        assert response.status_code == 422
        assert "private-input" not in response.text
        assert client.post("/v1/tasks", content="x" * 256001, headers=AUTH).status_code == 413


def test_gateway_read_continuation_does_not_skip_document_page():
    from paperagent_engine.product_gateway import GatewayTools

    content = json.dumps(
        {
            "tool": "project.document.extract",
            "summary": {"nextCursor": "2:0"},
            "locations": [{"text": "x" * 8000}],
        }
    )

    class Gateway:
        def tool(self, task_id, suffix, body):
            return {
                "path": body["path"],
                "sha256": body["expectedSha256"],
                "content": content,
                "truncated": True,
            }

    tools = GatewayTools(Gateway(), TASK, submission()["authority"])
    args = {"path": "paper.pdf", "expectedSha256": "c" * 64}
    first = tools.invoke("read_project_file", args)["output"]
    assert first["nextOffset"] == 6000
    assert first["nextDocumentCursor"] is None
    second = tools.invoke("read_project_file", {**args, "offset": 6000})["output"]
    assert first["content"] + second["content"] == content
    assert second["nextOffset"] is None
    assert second["nextDocumentCursor"] == "2:0"
    assert tools.invoke("read_project_file", {**args, "path": "../secret"})["success"] is False


@pytest.mark.parametrize(
    "instruction, expected",
    [("你好", "你好"), ("你好，你是什么模型？", "provider=test"), ("什么是快速排序？", "快速排序")],
)
def test_greeting_with_old_project_history_needs_one_model_and_no_tools(
    tmp_path, instruction, expected
):
    java = JavaGateway()
    data = submission()
    data["authority"]["instruction"] = instruction
    data["requestDigest"] = digest(data["authority"])
    data["context"] = {
        "historicalContext": {
            "earlierSummary": "Previously optimize Sort.java; do not continue unless asked"
        }
    }
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.post("/v1/tasks", json=data, headers=AUTH).status_code == 202
        assert finish(client)["state"] == "succeeded"
        assert java.models == 1
        assert not any("/workspace/" in path for path, _ in java.calls)
        assert next(e for e in java.events if e["type"] == "delivery")["conclusion"].startswith(
            expected
        )


def test_model_failure_is_visible_and_durable_without_fallback(tmp_path):
    class FailedJava(JavaGateway):
        def _handle(self, request):
            if request.url.path.endswith("/model-completions"):
                self.models += 1
                return httpx.Response(
                    502,
                    json={
                        "code": "MODEL_PROVIDER_QUOTA_EXHAUSTED",
                        "category": "model",
                        "message": "供应商报告余额不足 (HTTP 429)",
                        "retryable": False,
                    },
                )
            return super()._handle(request)

    java = FailedJava()
    with TestClient(make_app(tmp_path, java)) as client:
        client.post("/v1/tasks", json=submission(), headers=AUTH)
        view = finish(client)
        assert view["state"] == "failed"
        assert view["error"]["code"] == "MODEL_PROVIDER_QUOTA_EXHAUSTED"
        assert "余额不足" in view["error"]["message"]
        assert view["error"]["category"] == "model"
        assert java.models == 1
        assert java.events[-1]["error"] == view["error"]
    with TestClient(make_app(tmp_path, java)) as client:
        assert client.get(f"/v1/tasks/{TASK}", headers=AUTH).json()["error"] == view["error"]


def test_repeated_reads_reuse_pinned_result_and_stop_unproductive_loop(tmp_path):
    class RepeatingJava(JavaGateway):
        def _handle(self, request):
            response = super()._handle(request)
            if request.url.path.endswith("/model-completions") and self.models in {3, 4}:
                body = response.json()
                body["content"] = None
                body["toolCalls"] = [
                    {
                        "id": f"repeat-{self.models}",
                        "name": "read_project_file",
                        "arguments": json.dumps({"path": "paper.txt", "expectedSha256": "c" * 64}),
                    }
                ]
                return httpx.Response(200, json=body)
            return response

    java = RepeatingJava()
    with TestClient(make_app(tmp_path, java)) as client:
        client.post("/v1/tasks", json=submission(), headers=AUTH)
        view = finish(client)
        assert view["state"] == "failed"
        assert view["error"]["code"] == "REPEATED_TOOL_CALL_LIMIT"
        assert sum(p.endswith("/workspace/read") for p, _ in java.calls) == 1
        assert java.models == 4


def test_old_inflight_graph_is_not_resumed_with_new_nodes(tmp_path, monkeypatch):
    java = JavaGateway()
    with TestClient(make_app(tmp_path, java)) as client:
        service = client.app.state.service
        monkeypatch.setattr(service.pool, "submit", lambda *a: None)
        client.post("/v1/tasks", json=submission(), headers=AUTH)
        with service.runtime.store.transaction() as db:
            row = db.execute(
                "SELECT submission FROM product_bindings WHERE task_id=?", (TASK,)
            ).fetchone()
            frozen = json.loads(row[0])
            frozen.pop("pythonRuntime")
            db.execute(
                "UPDATE product_bindings SET submission=? WHERE task_id=?",
                (json.dumps(frozen), TASK),
            )
        result = client.post("/v1/tasks", json=submission(), headers=AUTH)
        assert result.status_code == 409
        assert result.json()["code"] == "PYTHON_RUNTIME_UPGRADE_REQUIRED"
        assert java.models == 0
