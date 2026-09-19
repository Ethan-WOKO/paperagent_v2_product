import pytest
from conftest import drive

from paperagent_engine.models import DemoModel
from paperagent_engine.runtime import Runtime


class ProcessLost(BaseException):
    pass


def test_restart_after_model_journal_commit_before_graph_checkpoint(tmp_path, submission):
    class Counting(DemoModel):
        calls = 0

        def plan(self, context):
            self.calls += 1
            return super().plan(context)

    model = Counting()
    engine = Runtime(tmp_path, model)
    task_id = engine.submit(submission)["task"]["task_id"]
    engine.advance(task_id)  # bootstrap
    original = engine._model

    def after_journal(*args):
        original(*args)
        raise ProcessLost()

    engine._model = after_journal
    with pytest.raises(ProcessLost):
        engine.advance(task_id)
    engine.close()
    engine = Runtime(tmp_path, model)
    try:
        assert drive(engine, task_id)["status"] == "succeeded"
        assert model.calls == 1
    finally:
        engine.close()


def test_restart_after_unknown_model_outcome_fails_without_retry(tmp_path, submission):
    class LostModel(DemoModel):
        calls = 0

        def plan(self, context):
            self.calls += 1
            raise ProcessLost()

    model = LostModel()
    engine = Runtime(tmp_path, model)
    task_id = engine.submit(submission)["task"]["task_id"]
    engine.advance(task_id)
    with pytest.raises(ProcessLost):
        engine.advance(task_id)
    engine.close()
    engine = Runtime(tmp_path, model)
    try:
        result = drive(engine, task_id)
        assert result["error"] == "OPERATION_OUTCOME_UNKNOWN"
        assert model.calls == 1
    finally:
        engine.close()


def test_restart_after_terminal_checkpoint_reconciles_delivery_once(tmp_path, submission):
    engine = Runtime(tmp_path)
    task_id = engine.submit(submission)["task"]["task_id"]
    while engine.view(task_id)["next"] != ["synthesize"]:
        engine.advance(task_id)
    original = engine._reconcile

    def crash(task_id, snapshot):
        if snapshot.values.get("delivery"):
            raise ProcessLost()
        original(task_id, snapshot)

    engine._reconcile = crash
    with pytest.raises(ProcessLost):
        engine.advance(task_id)
    engine.close()
    engine = Runtime(tmp_path)
    try:
        assert engine.view(task_id)["status"] == "running"
        assert engine.advance(task_id)["status"] == "succeeded"
        engine.advance(task_id)
        assert len([e for e in engine.store.events(task_id) if e["type"] == "delivery"]) == 1
    finally:
        engine.close()
