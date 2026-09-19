import json

import pytest
from fastapi.testclient import TestClient

from paperagent_engine.api import create_app

TOKEN = "python-dev-test-token-do-not-reuse"
HEADERS = {"Authorization": "Bearer " + TOKEN}


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path, TOKEN)) as client:
        yield client


def test_dev_api_contract_end_to_end(client, submission):
    health = client.get("/healthz").json()
    assert health["production_connected"] is False
    response = client.post("/dev/v1/tasks", json=submission.model_dump(), headers=HEADERS)
    assert response.status_code == 202
    task = response.json()["task"]
    url = "/dev/v1/tasks/" + task["task_id"]
    for _ in range(50):
        if task["status"] in {"succeeded", "failed"}:
            break
        response = client.post(
            url + "/advance", json={"expected_sequence": task["last_sequence"]}, headers=HEADERS
        )
        assert response.status_code == 200
        task = response.json()
    assert task["status"] == "succeeded", task
    assert client.get(url, headers=HEADERS).json() == task
    events = client.get(url + "/events", headers=HEADERS).json()["events"]
    assert len(events) == task["last_sequence"]
    assert client.get(url + "/events?after=" + str(len(events)), headers=HEADERS).json() == {
        "events": []
    }
    sse = client.get(
        url + "/events.sse", headers={**HEADERS, "Last-Event-ID": str(len(events) - 1)}
    )
    assert sse.headers["content-type"].startswith("text/event-stream")
    assert sse.text.count("data: ") == 1
    assert json.loads(sse.text.split("data: ")[1])["status"] == "succeeded"
    assert client.post(url + "/cancel", headers=HEADERS).json()["status"] == "succeeded"


@pytest.mark.parametrize(
    "path",
    ["/dev/v1/tasks/unknown", "/dev/v1/tasks/unknown/events", "/dev/v1/tasks/unknown/events.sse"],
)
def test_read_authorization_before_task_lookup(client, path):
    assert client.get(path).status_code == 401
    assert client.get(path, headers=HEADERS).status_code == 404


@pytest.mark.parametrize("suffix", ["/cancel", "/advance"])
def test_mutation_authorization(client, suffix):
    assert (
        client.post("/dev/v1/tasks/unknown" + suffix, json={"expected_sequence": 1}).status_code
        == 401
    )


def test_invalid_request_never_echoes_input(client, submission):
    data = submission.model_dump()
    data["secret"] = "never-echo-me"
    response = client.post("/dev/v1/tasks", json=data, headers=HEADERS)
    assert response.status_code == 422
    assert response.json() == {"code": "INVALID_REQUEST"}
    assert "never-echo-me" not in response.text


def test_existing_api_is_not_exposed(client, submission):
    assert (
        client.post("/v1/tasks", json=submission.model_dump(), headers=HEADERS).status_code == 404
    )
    assert not any(path.startswith("/v1/") for path in client.get("/openapi.json").json()["paths"])


def test_read_only_get_and_replay_do_not_advance(client, submission):
    response = client.post("/dev/v1/tasks", json=submission.model_dump(), headers=HEADERS).json()
    task = response["task"]
    url = "/dev/v1/tasks/" + task["task_id"]
    assert client.get(url, headers=HEADERS).json() == task
    assert client.post("/dev/v1/tasks", json=submission.model_dump(), headers=HEADERS).json() == {
        "task": task,
        "replayed": True,
    }
    advanced = client.post(url + "/advance", json={"expected_sequence": 1}, headers=HEADERS)
    assert advanced.status_code == 200
    stale = client.post(url + "/advance", json={"expected_sequence": 1}, headers=HEADERS)
    assert stale.status_code == 409
    assert stale.json()["code"] == "STALE_ADVANCE"


def test_request_size_limit(client):
    response = client.post("/dev/v1/tasks", content=b"x" * 160001, headers=HEADERS)
    assert response.status_code == 413


def test_invalid_event_cursor_rejected(client):
    response = client.get(
        "/dev/v1/tasks/unknown/events.sse", headers={**HEADERS, "Last-Event-ID": "-1"}
    )
    assert response.status_code == 422


def test_service_requires_dedicated_token(tmp_path):
    with pytest.raises(ValueError):
        create_app(tmp_path, "short")
