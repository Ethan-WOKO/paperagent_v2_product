import httpx
import pytest

from paperagent_engine.compare import collect, summarize


def test_failed_tasks_remain_in_denominator_but_not_latency_percentiles():
    rows = [
        {"engine": "PYTHON", "summary": {"state": state, "totalDurationMillis": ms}}
        for state, ms in [("succeeded", 100), ("succeeded", 200), ("failed", 1)]
    ]
    summary = summarize(rows)["PYTHON"]
    assert summary["tasks"] == 3
    assert summary["succeeded"] == 2
    assert summary["metrics"]["totalDurationMillis"] == {"n": 2, "p50": 100, "p95": 200}


def test_collector_is_read_only_and_rejects_unfinished_tasks():
    task = "task." + "a" * 64

    def handler(request):
        assert request.method == "GET"
        return httpx.Response(200, json={"taskId": task, "summary": {"state": "running"}})

    with httpx.Client(
        base_url="http://127.0.0.1:8080", transport=httpx.MockTransport(handler)
    ) as client:
        with pytest.raises(ValueError, match="finish"):
            collect(client, [{"engine": "TS", "turnId": 1, "taskId": task, "case": "reading"}])
