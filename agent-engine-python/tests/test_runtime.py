import json
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from conftest import drive

from paperagent_engine.contracts import Action, EngineError, Plan, StepResult, Submission, ToolCall
from paperagent_engine.models import DemoModel
from paperagent_engine.runtime import Runtime, context


def test_demo_completes_with_evidence_and_monotonic_events(runtime, submission):
    task_id = runtime.submit(submission)["task"]["task_id"]
    result = drive(runtime, task_id)
    assert result["status"] == "succeeded"
    assert len(result["completed"]) == 2
    assert all(c["evidence_refs"] for c in result["completed"])
    events = runtime.store.events(task_id)
    assert [e["sequence"] for e in events] == list(range(1, len(events) + 1))
    assert [e["type"] for e in events[-2:]] == ["delivery", "status"]
    assert events[-1]["status"] == "succeeded"
    assert "participants" not in json.dumps(events)
    assert "Chinese" not in json.dumps(events)
    count = len(events)
    assert runtime.advance(task_id) == result
    assert len(runtime.store.events(task_id)) == count


def test_submit_exact_replay_and_conflict(runtime, submission):
    first = runtime.submit(submission)
    assert runtime.submit(submission)["replayed"] is True
    changed = submission.model_copy(update={"documents": []})
    with pytest.raises(EngineError, match="REQUEST_CONFLICT"):
        runtime.submit(changed)
    assert first["task"]["status"] == "queued"


def test_restart_resumes_without_repeating_model_or_tools(tmp_path, submission):
    directory = tmp_path / "resume"
    engine = Runtime(directory)
    task_id = engine.submit(submission)["task"]["task_id"]
    for _ in range(7):
        engine.advance(task_id)
    before = engine.view(task_id)
    engine.close()
    engine = Runtime(directory)
    try:
        assert engine.view(task_id) == before  # construction and GET do not advance
        assert drive(engine, task_id)["status"] == "succeeded"
        rows = engine.store.connection.execute(
            "SELECT key FROM operations WHERE kind='tool'"
        ).fetchall()
        assert len(rows) == 3
    finally:
        engine.close()


def test_shared_data_directory_is_rejected(tmp_path):
    engine = Runtime(tmp_path)
    try:
        with pytest.raises(EngineError, match="DATA_DIRECTORY_IN_USE"):
            Runtime(tmp_path)
    finally:
        engine.close()


def test_cancel_queued_is_terminal_and_does_not_execute(runtime, submission):
    task_id = runtime.submit(submission)["task"]["task_id"]
    result = runtime.cancel(task_id)
    assert result["status"] == "cancelled"
    assert runtime.cancel(task_id) == result
    assert runtime.advance(task_id) == result
    assert runtime.store.connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0] == 0


def test_stale_advance_does_not_consume_next_step(runtime, submission):
    task = runtime.submit(submission)["task"]
    runtime.advance(task["task_id"], task["last_sequence"])
    with pytest.raises(EngineError, match="STALE_ADVANCE"):
        runtime.advance(task["task_id"], task["last_sequence"])


class BadEvidence(DemoModel):
    def act(self, context, tools):
        return Action(
            result=StepResult(
                kind="complete", summary="Invented completion", evidence_refs=["not_observed"]
            )
        )


def test_fabricated_completion_fails_closed(tmp_path, submission):
    engine = Runtime(tmp_path, BadEvidence())
    try:
        result = drive(engine, engine.submit(submission)["task"]["task_id"])
        assert result["error"] == "STEP_EVIDENCE_INVALID"
        assert result["delivery"] is None
    finally:
        engine.close()


class Replanning(DemoModel):
    def plan(self, context):
        if context["completed"]:
            return Plan.model_validate(
                {
                    "steps": [
                        {
                            "key": "revised",
                            "objective": "Inspect evidence",
                            "done_when": "Documents read",
                        }
                    ]
                }
            )
        return super().plan(context)

    def act(self, context, tools):
        if context["step"]["key"] == "inspect":
            return Action(
                result=StepResult(kind="replan", summary="Need a revised inspection step")
            )
        return super().act(context, tools)


def test_replan_preserves_completed_facts(tmp_path, submission):
    engine = Runtime(tmp_path, Replanning())
    try:
        task_id = engine.submit(submission)["task"]["task_id"]
        while not engine.view(task_id)["completed"]:
            engine.advance(task_id)
        completed = engine.view(task_id)["completed"][0]
        result = drive(engine, task_id)
        assert result["status"] == "succeeded"
        assert result["revision"] == 2
        assert result["completed"][0] == completed
    finally:
        engine.close()


class Rewrite(Replanning):
    def plan(self, context):
        return DemoModel.plan(self, context)


def test_replan_cannot_reuse_completed_key(tmp_path, submission):
    engine = Runtime(tmp_path, Rewrite())
    try:
        result = drive(engine, engine.submit(submission)["task"]["task_id"])
        assert result["error"] == "COMPLETED_STEP_REWRITE"
        assert len(result["completed"]) == 1
    finally:
        engine.close()


class EndlessReplan(DemoModel):
    def act(self, context, tools):
        return Action(result=StepResult(kind="replan", summary="Try another plan"))


def test_replans_are_bounded(tmp_path, submission):
    engine = Runtime(tmp_path, EndlessReplan())
    try:
        result = drive(engine, engine.submit(submission)["task"]["task_id"])
        assert result["error"] == "REPLAN_LIMIT_EXCEEDED"
        assert result["revision"] == 3
    finally:
        engine.close()


class EndlessTools(DemoModel):
    def act(self, context, tools):
        return Action(call=ToolCall(name="list_documents", arguments={}))


def test_step_tool_loop_is_bounded(tmp_path, submission):
    engine = Runtime(tmp_path, EndlessTools())
    try:
        result = drive(engine, engine.submit(submission)["task"]["task_id"])
        assert result["error"] == "STEP_BUDGET_EXCEEDED"
    finally:
        engine.close()


class BrokenModel(DemoModel):
    def plan(self, context):
        raise RuntimeError("secret-token and C:/private/file")


def test_provider_failure_is_sanitized(tmp_path, submission):
    engine = Runtime(tmp_path, BrokenModel())
    try:
        task_id = engine.submit(submission)["task"]["task_id"]
        result = drive(engine, task_id)
        assert result["error"] == "ENGINE_EXECUTION_FAILED"
        assert "secret-token" not in json.dumps([result, engine.store.events(task_id)])
        snapshot = engine.graph.get_state(engine.config(task_id))
        assert "secret-token" not in repr(snapshot.tasks)
    finally:
        engine.close()


def test_cancel_during_model_call_prevents_next_tool(tmp_path, submission):
    entered, release = Event(), Event()

    class Blocking(DemoModel):
        def act(self, context, tools):
            entered.set()
            assert release.wait(5)
            return super().act(context, tools)

    engine = Runtime(tmp_path, Blocking())
    try:
        task_id = engine.submit(submission)["task"]["task_id"]
        engine.advance(task_id)
        engine.advance(task_id)
        with ThreadPoolExecutor() as pool:
            future = pool.submit(engine.advance, task_id)
            try:
                assert entered.wait(5)
                with pytest.raises(EngineError, match="ENGINE_BUSY"):
                    engine.advance(task_id)
                assert engine.cancel(task_id)["status"] == "cancelled"
            finally:
                release.set()
            assert future.result()["status"] == "cancelled"
        assert (
            engine.store.connection.execute(
                "SELECT COUNT(*) FROM operations WHERE kind='tool'"
            ).fetchone()[0]
            == 0
        )
    finally:
        engine.close()


def test_context_isolates_previous_steps_and_preserves_frame(runtime, submission):
    task_id = runtime.submit(submission)["task"]["task_id"]
    while not runtime.view(task_id)["completed"]:
        runtime.advance(task_id)
    state = runtime.graph.get_state(runtime.config(task_id)).values
    selected = context(state)
    assert selected["frame"] == submission.frame.model_dump()
    assert selected["observations"] == []
    assert selected["completed"][0]["evidence_refs"]
    assert "participants" not in json.dumps(selected)


def test_memory_port_is_frozen_once_and_not_evidence(tmp_path, submission):
    class MemoryAdapter:
        calls = 0

        def recall(self, request):
            self.calls += 1
            return [{"id": "pref", "content": "Use Chinese"}]

    adapter = MemoryAdapter()
    engine = Runtime(tmp_path, memory=adapter)
    try:
        task_id = engine.submit(submission)["task"]["task_id"]
        assert drive(engine, task_id)["status"] == "succeeded"
        assert adapter.calls == 1
    finally:
        engine.close()


def test_tasks_have_separate_documents_memories_and_checkpoints(runtime, submission):
    first = runtime.submit(submission)["task"]["task_id"]
    other = Submission.model_validate(
        {
            **submission.model_dump(),
            "client_request_id": "other",
            "documents": [{"id": "private", "text": "other-task-only"}],
            "memories": [],
        }
    )
    second = runtime.submit(other)["task"]["task_id"]
    drive(runtime, first)
    assert runtime.view(second)["status"] == "queued"
    drive(runtime, second)
    state = runtime.graph.get_state(runtime.config(second)).values
    assert "participants" not in json.dumps(state)
    assert state["memories"] == []
