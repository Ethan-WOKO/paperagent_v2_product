import pytest

from paperagent_engine.contracts import EngineError


def test_journal_replays_exact_result_without_reinvoking(runtime, submission):
    task_id = runtime.submit(submission)["task"]["task_id"]
    calls = []

    def invoke():
        calls.append(1)
        return {"success": True}

    for _ in range(2):
        assert runtime.store.operation(task_id, "key", "tool", {"a": 1}, invoke) == {
            "success": True
        }
    assert calls == [1]
    with pytest.raises(EngineError, match="OPERATION_CONFLICT"):
        runtime.store.operation(task_id, "key", "tool", {"a": 2}, invoke)


def test_uncertain_operation_is_not_repeated(runtime, submission):
    task_id = runtime.submit(submission)["task"]["task_id"]
    calls = []

    def crash():
        calls.append(1)
        raise SystemExit("simulated process loss")

    with pytest.raises(SystemExit):
        runtime.store.operation(task_id, "pending", "model", {}, crash)
    with pytest.raises(EngineError, match="OPERATION_OUTCOME_UNKNOWN"):
        runtime.store.operation(task_id, "pending", "model", {}, crash)
    assert len(calls) == 1


def test_model_budget_includes_failed_attempt_reservations(runtime, submission):
    task_id = runtime.submit(submission)["task"]["task_id"]
    for i in range(20):
        runtime.store.operation(task_id, str(i), "model", {}, lambda: {})
    with pytest.raises(EngineError, match="BUDGET_EXCEEDED"):
        runtime.store.operation(task_id, "overflow", "model", {}, lambda: {})
    assert runtime.store.operation(task_id, "0", "model", {}, lambda: {}) == {}
