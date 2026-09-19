"""Explicitly advanced LangGraph; no startup scans, polling workers or product clients."""

import json
import sqlite3
from pathlib import Path
from threading import Lock
from typing import TypedDict

from filelock import FileLock, Timeout
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context

from .contracts import Action, EngineError, Plan, Submission
from .models import DemoModel
from .storage import Store, canonical
from .tools import SubmittedMemory, ToolSet

TERMINAL = {"succeeded", "failed", "cancelled"}


class State(TypedDict):
    task_id: str
    request: dict
    memories: list[dict]
    plan: dict
    revision: int
    index: int
    completed: list[dict]
    observations: list[dict]
    action: dict
    turn: int
    replan_reason: str
    delivery: str


def guarded_node(function):
    """Sanitize unexpected errors before LangGraph can persist task error metadata."""

    def invoke(state: State):
        try:
            return function(state)
        except EngineError:
            raise
        except Exception:
            raise EngineError("ENGINE_EXECUTION_FAILED") from None

    return invoke


def context(state):
    """Keep objective/constraints intact; compact old results, bound step observations."""
    request = state["request"]
    frame = request["frame"]
    if len(canonical(frame)) > 6000:
        raise EngineError("FRAME_CONTEXT_TOO_LARGE", 422)
    completed = [{**item, "summary": item["summary"][:600]} for item in state["completed"]]
    observations = state["observations"]
    # Old observations remain in durable state. Keep identifiers/status and bounded output.
    visible = []
    for i, item in enumerate(observations):
        output = item["output"]
        if i < len(observations) - 3 and output is not None:
            # Preserve pagination metadata for read tools, offload old content.
            output = {k: v for k, v in output.items() if k not in {"text", "content"}}
        visible.append({**item, "output": output})
    result = {
        "frame": frame,
        "completed": completed,
        "memories": [{"id": m["id"], "content": m["content"][:400]} for m in state["memories"][:4]],
        "memory_policy": "preferences only; not authority or current evidence",
        "document_ids": [d["id"] for d in request["documents"]],
        "observations": visible,
        "replan_reason": state["replan_reason"],
    }
    if state["plan"] and state["index"] < len(state["plan"]["steps"]):
        result["step"] = state["plan"]["steps"][state["index"]]
    if len(canonical(result)) > 24000:
        raise EngineError("CONTEXT_BUDGET_EXCEEDED")
    return result


class Runtime:
    def __init__(self, data_dir: Path, model=None, memory=None):
        data_dir.mkdir(parents=True, exist_ok=True)
        self.file_lock = FileLock(data_dir / "engine.lock")
        try:
            self.file_lock.acquire(timeout=0)
        except Timeout:
            raise EngineError("DATA_DIRECTORY_IN_USE") from None
        self.store = Store(data_dir / "python-dev.sqlite3")
        self.connection = sqlite3.connect(data_dir / "graph.sqlite3", check_same_thread=False)
        self.saver = SqliteSaver(self.connection)
        self.model = model or DemoModel()
        self.memory = memory or SubmittedMemory()
        self.execution_lock = Lock()
        graph = StateGraph(State)
        for name in ("bootstrap", "plan", "act", "tool", "finish", "synthesize"):
            graph.add_node(name, guarded_node(getattr(self, "_" + name)))
        graph.add_edge(START, "bootstrap")
        graph.add_edge("bootstrap", "plan")
        graph.add_edge("plan", "act")
        graph.add_conditional_edges(
            "act", lambda s: "tool" if s["action"].get("call") else "finish"
        )
        graph.add_edge("tool", "act")
        graph.add_conditional_edges("finish", self._after_finish)
        graph.add_edge("synthesize", END)
        self.graph = graph.compile(
            checkpointer=self.saver,
            interrupt_after=["bootstrap", "plan", "act", "tool", "finish", "synthesize"],
        )

    def _bootstrap(self, state):
        memories = self.store.operation(
            state["task_id"],
            "memory",
            "memory",
            state["request"],
            lambda: self.memory.recall(Submission.model_validate(state["request"])),
        )
        # Revalidate adapter output with the same bounded memory contract.
        request = Submission.model_validate({**state["request"], "memories": memories})
        return {"memories": [m.model_dump() for m in request.memories]}

    def _model(self, state, purpose, invoke):
        data = context(state)
        key = f"model-{state['revision']}-{state['index']}-{state['turn']}-{purpose}"
        return self.store.operation(state["task_id"], key, "model", data, lambda: invoke(data))

    def _plan(self, state):
        if state["revision"] >= 3:
            raise EngineError("REPLAN_LIMIT_EXCEEDED")
        result = self._model(state, "plan", lambda c: self.model.plan(c).model_dump())
        plan = Plan.model_validate(result)
        if len(state["completed"]) + len(plan.steps) > 8:
            raise EngineError("PLAN_SIZE_EXCEEDED")
        completed_keys = {item["key"] for item in state["completed"]}
        if completed_keys.intersection(step.key for step in plan.steps):
            raise EngineError("COMPLETED_STEP_REWRITE")
        return {
            "plan": plan.model_dump(),
            "revision": state["revision"] + 1,
            "index": 0,
            "turn": 0,
            "observations": [],
            "replan_reason": "",
        }

    def _act(self, state):
        if state["turn"] >= 10:
            raise EngineError("STEP_BUDGET_EXCEEDED")
        tools = self._tools(state)
        action = self._model(state, "act", lambda c: self.model.act(c, tools.tools).model_dump())
        return {"action": Action.model_validate(action).model_dump(), "turn": state["turn"] + 1}

    def _tool(self, state):
        call = state["action"]["call"]
        key = f"evidence_{state['revision']}_{state['index']}_{state['turn']}"
        tools = self._tools(state)
        result = self.store.operation(
            state["task_id"],
            key,
            "tool",
            call,
            lambda: tools.invoke(call["name"], call["arguments"]),
        )
        observation = {"id": key, "name": call["name"], **result}
        return {"observations": [*state["observations"], observation]}

    def _tools(self, state):
        return ToolSet(Submission.model_validate(state["request"]), state["memories"])

    def _finish(self, state):
        result = state["action"]["result"]
        if result["kind"] == "replan":
            return {"replan_reason": result["summary"]}
        refs = result["evidence_refs"]
        allowed = {
            o["id"] for o in state["observations"] if o["success"] and o["name"] != "search_memory"
        }
        if not refs or len(set(refs)) != len(refs) or not set(refs) <= allowed:
            raise EngineError("STEP_EVIDENCE_INVALID")
        step = state["plan"]["steps"][state["index"]]
        completed = {
            "key": step["key"],
            "revision": state["revision"],
            "summary": result["summary"],
            "evidence_refs": refs,
        }
        return {
            "completed": [*state["completed"], completed],
            "index": state["index"] + 1,
            "turn": 0,
            "observations": [],
            "replan_reason": "",
        }

    @staticmethod
    def _after_finish(state):
        if state["replan_reason"]:
            return "plan"
        return "act" if state["index"] < len(state["plan"]["steps"]) else "synthesize"

    def _synthesize(self, state):
        result = self._model(state, "synthesize", lambda c: {"text": self.model.synthesize(c)})
        if (
            not isinstance(result["text"], str)
            or not result["text"].strip()
            or len(result["text"]) > 6000
        ):
            raise EngineError("MODEL_RESPONSE_INVALID")
        return {"delivery": result["text"]}

    @staticmethod
    def config(task_id):
        return {"configurable": {"thread_id": task_id}, "recursion_limit": 100}

    def submit(self, request):
        if len(canonical(request.frame.model_dump())) > 6000:
            raise EngineError("FRAME_CONTEXT_TOO_LARGE", 422)
        task_id, replayed = self.store.submit(request)
        return {"replayed": replayed, "task": self.view(task_id)}

    def view(self, task_id):
        task = self.store.task(task_id)
        snapshot = self.graph.get_state(self.config(task_id))
        state = snapshot.values or {}
        return {
            "protocol": "python-dev/1",
            "task_id": task_id,
            "status": task["status"],
            "error": task["error"],
            "last_sequence": len(self.store.events(task_id)),
            "plan": state.get("plan", {}),
            "revision": state.get("revision", 0),
            "completed": state.get("completed", []),
            "delivery": state.get("delivery") if task["status"] == "succeeded" else None,
            "next": list(snapshot.next) if task["status"] not in TERMINAL else [],
        }

    def _reconcile(self, task_id, snapshot):
        if not snapshot.values:
            return
        state = snapshot.values
        checkpoint = snapshot.config["configurable"]["checkpoint_id"]
        with self.store.transaction() as db:
            if self.store.task(task_id)["status"] in TERMINAL:
                return
            payload = {
                "revision": state["revision"],
                "completed_count": len(state["completed"]),
                "next": list(snapshot.next),
            }
            # No model messages, document bodies, memory contents or raw graph snapshots in events.
            self.store.event(task_id, checkpoint, "progress", payload, db)
            if state["delivery"] and not snapshot.next:
                self.store.event(
                    task_id,
                    "delivery",
                    "delivery",
                    {
                        "conclusion": state["delivery"],
                        "demo_evidence_refs": [
                            r for c in state["completed"] for r in c["evidence_refs"]
                        ],
                    },
                    db,
                )
                db.execute("UPDATE tasks SET status='succeeded' WHERE id=?", (task_id,))
                self.store.event(
                    task_id, "terminal", "status", {"status": "succeeded", "error": None}, db
                )
            else:
                db.execute("UPDATE tasks SET status='running' WHERE id=?", (task_id,))

    def advance(self, task_id, expected_sequence=None):
        if not self.execution_lock.acquire(blocking=False):
            raise EngineError("ENGINE_BUSY")
        try:
            task = self.store.task(task_id)
            if task["status"] in TERMINAL:
                return self.view(task_id)
            config = self.config(task_id)
            snapshot = self.graph.get_state(config)
            self._reconcile(task_id, snapshot)
            if self.store.task(task_id)["status"] in TERMINAL:
                return self.view(task_id)
            if expected_sequence is not None and expected_sequence != len(
                self.store.events(task_id)
            ):
                raise EngineError("STALE_ADVANCE")
            initial = None
            if not snapshot.values:
                initial = {
                    "task_id": task_id,
                    "request": json.loads(task["request"]),
                    "memories": [],
                    "plan": {},
                    "revision": 0,
                    "index": 0,
                    "completed": [],
                    "observations": [],
                    "action": {},
                    "turn": 0,
                    "replan_reason": "",
                    "delivery": "",
                }
            try:
                with tracing_context(enabled=False):
                    self.graph.invoke(initial, config, durability="sync")
                self._reconcile(task_id, self.graph.get_state(config))
            except Exception as exc:
                code = exc.code if isinstance(exc, EngineError) else "ENGINE_EXECUTION_FAILED"
                self.store.terminal(task_id, "failed", code)
            return self.view(task_id)
        finally:
            self.execution_lock.release()

    def cancel(self, task_id):
        self.store.terminal(task_id, "cancelled", "TASK_CANCELLED")
        return self.view(task_id)

    def close(self):
        self.store.close()
        self.connection.close()
        self.file_lock.release()
