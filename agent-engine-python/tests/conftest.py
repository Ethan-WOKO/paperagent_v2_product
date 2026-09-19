import pytest

from paperagent_engine.cli import demo_submission
from paperagent_engine.runtime import TERMINAL, Runtime


@pytest.fixture
def submission():
    return demo_submission()


@pytest.fixture
def runtime(tmp_path):
    engine = Runtime(tmp_path / "engine")
    yield engine
    engine.close()


def drive(engine, task_id, limit=100):
    for _ in range(limit):
        view = engine.view(task_id)
        if view["status"] in TERMINAL:
            return view
        engine.advance(task_id)
    raise AssertionError("Engine did not terminate within the test bound")
