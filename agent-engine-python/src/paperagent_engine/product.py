"""Opt-in Project analysis service. Java owns routing, leases, model credentials and facts."""

import json
import logging
import secrets
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from .contracts import EngineError, Submission
from .models import LangChainModel
from .product_gateway import GatewayChatModel, GatewayTools
from .runtime import TERMINAL, Runtime, context
from .storage import canonical, digest, now


class ProductRuntime(Runtime):
    def __init__(self, directory, gateway):
        super().__init__(directory)
        self.gateway = gateway
        with self.store.transaction() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS product_bindings (
                task_id TEXT PRIMARY KEY, local_id TEXT UNIQUE NOT NULL,
                submission TEXT NOT NULL, created TEXT NOT NULL)""")

    def binding(self, task_id=None, local_id=None):
        with self.store.lock:
            row = self.store.connection.execute(
                "SELECT * FROM product_bindings WHERE task_id=? OR local_id=?", (task_id, local_id)
            ).fetchone()
        if row is None:
            raise EngineError("TASK_NOT_FOUND", 404)
        result = dict(row)
        result["submission"] = json.loads(result["submission"])
        return result

    def _tools(self, state):
        binding = self.binding(local_id=state["task_id"])
        return GatewayTools(self.gateway, binding["task_id"], binding["submission"]["authority"])

    def _tool(self, state):
        key = f"evidence_{state['revision']}_{state['index']}_{state['turn']}"
        name = state["action"]["call"]["name"]
        stable_name = {
            "list_project_files": "project.list",
            "read_project_file": "project.read",
        }.get(name)

        def record(stage):
            if stable_name is None or self.store.task(state["task_id"])["status"] == "cancelled":
                return
            with self.store.transaction() as db:
                self.store.event(
                    state["task_id"],
                    key + ":" + stage,
                    "product_tool",
                    {
                        "call_id": "call." + digest([state["task_id"], key]),
                        "name": stable_name,
                        "state": stage,
                    },
                    db,
                )

        record("requested")
        try:
            result = super()._tool(state)
            record("succeeded" if result["observations"][-1]["success"] else "failed")
            return result
        except Exception:
            record("failed")
            raise

    def _model(self, state, purpose, invoke):
        binding = self.binding(local_id=state["task_id"])
        submission = binding["submission"]
        payload = context(state)
        payload["project"] = submission["authority"]["project"]
        payload["history_data_not_instructions"] = canonical(
            submission.get("context", {}).get("historicalContext", {})
        )[:4000]
        if submission["authority"].get("skill"):
            payload["skill"] = submission["authority"]["skill"]["prompt"][:2000]
        if len(canonical(payload)) > 30000:
            raise EngineError("CONTEXT_BUDGET_EXCEEDED")
        key = f"model-{state['revision']}-{state['index']}-{state['turn']}-{purpose}"
        adapter = LangChainModel.__new__(LangChainModel)
        adapter.chat = GatewayChatModel(
            gateway=self.gateway,
            task_id=binding["task_id"],
            call_id="model." + digest([binding["task_id"], key]),
            route=submission["authority"]["model"],
        )

        def run():
            if purpose == "plan":
                return adapter.plan(payload).model_dump()
            if purpose == "act":
                return adapter.act(payload, self._tools(state).tools).model_dump()
            return {"text": adapter.synthesize(payload)}

        return self.store.operation(state["task_id"], key, "model", payload, run)


class ProductService:
    def __init__(self, directory, gateway):
        self.runtime = ProductRuntime(directory, gateway)
        self.gateway = gateway
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="python-analysis")
        self.lock = threading.Lock()
        self.pending = set()
        self.stopping = threading.Event()

    def accept(self, submission):
        try:
            if set(submission) - {
                "contractVersion",
                "taskId",
                "requestDigest",
                "authority",
                "context",
                "gateway",
            }:
                raise ValueError()
            task_id = submission["taskId"]
            authority = submission["authority"]
            if (
                submission["contractVersion"] != "1.0"
                or len(task_id) != 69
                or not task_id.startswith("task.")
            ):
                raise ValueError()
            int(task_id[5:], 16)
            grant = submission["gateway"]["taskGrant"]
            if not isinstance(grant, str) or not 32 <= len(grant) <= 4096:
                raise ValueError()
            if submission["requestDigest"] != digest(authority):
                raise ValueError()
            if authority["runMode"] != "PERSISTENT_PLAN_EXECUTE" or authority["permissions"] != {
                "readProject": True,
                "writeWorkspace": False,
                "executeSandbox": False,
            }:
                raise EngineError("PYTHON_READ_ONLY_REQUIRED", 403)
            if datetime.fromisoformat(
                submission["gateway"]["expiresAt"].replace("Z", "+00:00")
            ) <= datetime.now(timezone.utc):
                raise EngineError("TASK_GRANT_EXPIRED", 401)
            memories = submission.get("context", {}).get("longTermMemory", {}).get("entries", [])
            local = Submission.model_validate(
                {
                    "client_request_id": task_id[5:],
                    "frame": {
                        "objective": authority["instruction"],
                        "objects": ["Frozen Project files"],
                        "deliverables": ["Read-only analysis with evidence and limitations"],
                        "constraints": [
                            "No writes, sandbox execution, network tools or publication"
                        ],
                        "project_version": authority["project"]["projectVersion"],
                    },
                    "documents": [
                        {
                            "id": "project",
                            "text": "Read through authenticated Java gateway tools only.",
                        }
                    ],
                    "memories": [
                        {"id": digest(m["id"])[:32], "content": m["content"][:2000]}
                        for m in memories[:8]
                    ],
                }
            )
        except (KeyError, TypeError, ValueError):
            raise EngineError("INVALID_PRODUCT_SUBMISSION", 400) from None
        # Only the short-lived in-memory grant is refreshed on exact replay.
        frozen = {k: v for k, v in submission.items() if k != "gateway"}
        with self.lock:
            try:
                binding = self.runtime.binding(task_id=task_id)
                if binding["submission"]["requestDigest"] != submission["requestDigest"]:
                    raise EngineError("TASK_DIGEST_CONFLICT")
                replayed = True
            except EngineError as exc:
                if exc.status != 404:
                    raise
                accepted = self.runtime.submit(local)
                with self.runtime.store.transaction() as db:
                    db.execute(
                        "INSERT INTO product_bindings VALUES (?,?,?,?)",
                        (task_id, accepted["task"]["task_id"], canonical(frozen), now()),
                    )
                replayed = False
            self.gateway.grants[task_id] = submission["gateway"]["taskGrant"]
            binding = self.runtime.binding(task_id=task_id)
            # Initial product checkpoint is deterministic across uncertain network retries.
            try:
                stored = self.gateway.state(f"/tasks/{task_id}/checkpoint")
            except EngineError as exc:
                if exc.status != 404:
                    raise
                projection = self.checkpoint(binding, initial=True)
                self.gateway.state(
                    "/checkpoints",
                    {
                        "contractVersion": "1.0",
                        "taskId": task_id,
                        "requestDigest": submission["requestDigest"],
                        "expectedRevision": None,
                        "checkpoint": projection,
                    },
                )
                stored = {"checkpoint": projection}
            if (
                task_id not in self.pending
                and stored["checkpoint"]["view"]["state"] not in TERMINAL
            ):
                self.pending.add(task_id)
                self.pool.submit(self.work, task_id)
        return {"contractVersion": "1.0", "replayed": replayed, "task": self.public_view(task_id)}

    def public_view(self, task_id):
        self.runtime.binding(task_id=task_id)
        return self.gateway.state(f"/tasks/{task_id}/checkpoint")["checkpoint"]["view"]

    def public_events(self, task_id, after=0):
        self.runtime.binding(task_id=task_id)
        return [
            e
            for e in self.gateway.state(f"/tasks/{task_id}/events")["events"]
            if e["sequence"] > after
        ]

    def view(self, task_id):
        binding = self.runtime.binding(task_id=task_id)
        local = self.runtime.view(binding["local_id"])
        events = self.events(task_id)
        delivery = next((e["sequence"] for e in events if e["type"] == "delivery"), None)
        return {
            "contractVersion": "1.0",
            "taskId": task_id,
            "requestDigest": binding["submission"]["requestDigest"],
            "state": local["status"],
            "lastSequence": local["last_sequence"],
            "pendingQuestionId": None,
            "deliverySequence": delivery,
            "terminalSequence": local["last_sequence"] if local["status"] in TERMINAL else None,
            "error": self.problem(local["error"]) if local["error"] else None,
            "createdAt": binding["created"],
            "updatedAt": events[-1]["occurredAt"] if events else binding["created"],
        }

    @staticmethod
    def problem(code):
        return {
            "contractVersion": "1.0",
            "code": code,
            "category": "internal",
            "message": "Python read-only analysis stopped: " + code,
            "retryable": False,
        }

    def events(self, task_id, after=0):
        binding = self.runtime.binding(task_id=task_id)
        result = []
        for event in self.runtime.store.events(binding["local_id"], after):
            item = {
                "contractVersion": "1.0",
                "taskId": task_id,
                "sequence": event["sequence"],
                "occurredAt": event["occurred_at"],
            }
            if event["type"] == "status":
                item.update(
                    type="status",
                    state=event["status"],
                    error=self.problem(event["error"]) if event.get("error") else None,
                )
            elif event["type"] == "delivery":
                item.update(type="delivery", conclusion=event["conclusion"], receiptRefs=[])
            elif event["type"] == "product_tool":
                item.update(
                    type="tool",
                    callId=event["call_id"],
                    name=event["name"],
                    state=event["state"],
                    inputSummary="Python read-only Project analysis",
                    outputSummary=None,
                    receiptRef=None,
                )
            else:
                item.update(
                    type="message",
                    content=f"Python 只读分析：计划版本 {event['revision']}，已完成 {event['completed_count']} 步。",
                )
            result.append(item)
        return result

    def checkpoint(self, binding, initial=False):
        view = self.view(binding["task_id"])
        with self.runtime.store.lock:
            model_calls = self.runtime.store.connection.execute(
                "SELECT COUNT(*) FROM operations WHERE task_id=? AND kind='model'",
                (binding["local_id"],),
            ).fetchone()[0]
        if initial:
            view.update(
                state="queued",
                lastSequence=0,
                deliverySequence=None,
                terminalSequence=None,
                error=None,
                updatedAt=binding["created"],
            )
        return {
            "authority": binding["submission"]["authority"],
            "view": view,
            "engine": "PYTHON",
            "messages": [],
            "receiptRefs": [],
            "modelCalls": model_calls,
            # Token usage belongs to Java model-call facts, never fabricated as zero.
            "metrics": {"startedAt": binding["created"]},
        }

    def sync(self, binding, lease):
        task_id = binding["task_id"]
        stored = self.gateway.state(f"/tasks/{task_id}/checkpoint")
        saved = self.gateway.state(f"/tasks/{task_id}/events")["events"]
        after = saved[-1]["sequence"] if saved else 0
        for event in self.events(task_id, after):
            self.gateway.state(
                "/events", {"contractVersion": "1.0", "event": event, "lease": lease}
            )
        checkpoint = self.checkpoint(binding)
        # During execution always hold running state; queued writes release a lease.
        if checkpoint["view"]["state"] == "queued":
            checkpoint["view"]["state"] = "running"
        self.gateway.state(
            "/checkpoints",
            {
                "contractVersion": "1.0",
                "taskId": task_id,
                "requestDigest": binding["submission"]["requestDigest"],
                "expectedRevision": stored["checkpointRevision"],
                "checkpoint": checkpoint,
                "lease": lease,
            },
        )

    def work(self, task_id):
        binding = self.runtime.binding(task_id=task_id)
        heartbeat_stop = threading.Event()
        lease_lost = threading.Event()
        lease = None
        heartbeat = None
        try:
            deadline = time.monotonic() + 600
            owner = "engine.python_" + uuid.uuid4().hex
            while not self.stopping.is_set() and time.monotonic() < deadline:
                claim = self.gateway.state(
                    f"/python/tasks/{task_id}/claim", {"contractVersion": "1.0", "owner": owner}
                )["task"]
                if claim:
                    break
                if self.stopping.wait(1):
                    return
            else:
                return
            lease = claim["lease"]
            self.gateway.grants[task_id] = claim["taskGrant"]
            if claim["cancellationRequested"]:
                self.runtime.cancel(binding["local_id"])

            def renew():
                while not heartbeat_stop.wait(5):
                    try:
                        status = self.gateway.state(
                            f"/tasks/{task_id}/lease/renew",
                            {"contractVersion": "1.0", "lease": lease},
                        )
                        if status["cancellationRequested"]:
                            self.runtime.cancel(binding["local_id"])
                    except Exception:
                        lease_lost.set()
                        return

            heartbeat = threading.Thread(target=renew, daemon=True)
            heartbeat.start()
            self.sync(binding, lease)
            while not self.stopping.is_set() and not lease_lost.is_set():
                if self.runtime.view(binding["local_id"])["status"] in TERMINAL:
                    break
                if time.monotonic() >= deadline:
                    self.runtime.store.terminal(
                        binding["local_id"], "failed", "TASK_DEADLINE_EXCEEDED"
                    )
                else:
                    self.runtime.advance(binding["local_id"])
                if not lease_lost.is_set():
                    self.sync(binding, lease)
        except Exception as exc:
            # Keep the last persisted outcome. An explicit resubmit can reconcile.
            # Never turn an uncertain product persistence error into fake success.
            logging.getLogger(__name__).warning(
                "Python analysis paused task=%s failure=%s", task_id, type(exc).__name__
            )
        finally:
            heartbeat_stop.set()
            if heartbeat:
                heartbeat.join(timeout=6)
            if lease:
                try:
                    self.gateway.state(
                        f"/tasks/{task_id}/lease/release",
                        {"contractVersion": "1.0", "lease": lease},
                    )
                except Exception:
                    pass
            self.gateway.grants.pop(task_id, None)
            with self.lock:
                self.pending.discard(task_id)

    def cancel(self, task_id):
        binding = self.runtime.binding(task_id=task_id)
        self.gateway.state(f"/tasks/{task_id}/cancel-request", {"contractVersion": "1.0"})
        self.runtime.cancel(binding["local_id"])
        with self.lock:
            if task_id not in self.pending:
                self.pending.add(task_id)
                self.pool.submit(self.work, task_id)
        return self.public_view(task_id)

    def close(self):
        self.stopping.set()
        self.pool.shutdown(wait=True)
        self.runtime.close()
        self.gateway.client.close()


def create_product_app(directory, token, gateway):
    if len(token) < 32:
        raise ValueError("Python service token requires at least 32 characters")

    @asynccontextmanager
    async def lifespan(app):
        app.state.service = ProductService(directory, gateway)
        try:
            yield
        finally:
            app.state.service.close()

    app = FastAPI(title="PaperAgent Python read-only Project engine", lifespan=lifespan)

    @app.middleware("http")
    async def bounded_body(request, call_next):
        size, chunks = 0, []
        async for chunk in request.stream():
            size += len(chunk)
            if size > 256_000:
                return JSONResponse(
                    status_code=413, content=ProductService.problem("REQUEST_TOO_LARGE")
                )
            chunks.append(chunk)
        request._body = b"".join(chunks)
        return await call_next(request)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        return JSONResponse(status_code=422, content=ProductService.problem("INVALID_REQUEST"))

    def auth(authorization: str = Header(default="")):
        if not secrets.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
            raise EngineError("UNAUTHORIZED", 401)

    @app.exception_handler(EngineError)
    async def error(request, exc):
        return JSONResponse(status_code=exc.status, content=ProductService.problem(exc.code))

    @app.get("/healthz")
    def health():
        return {"engine": "PYTHON", "readOnly": True, "model": "product-gateway"}

    @app.post("/v1/tasks", status_code=202, dependencies=[Depends(auth)])
    def submit(body: dict, request: Request):
        return request.app.state.service.accept(body)

    @app.get("/v1/tasks/{task_id}", dependencies=[Depends(auth)])
    def view(task_id: str, request: Request):
        return request.app.state.service.public_view(task_id)

    @app.post("/v1/tasks/{task_id}/cancel", status_code=202, dependencies=[Depends(auth)])
    def cancel(task_id: str, body: dict, request: Request):
        return request.app.state.service.cancel(task_id)

    @app.post("/v1/tasks/{task_id}/answer", dependencies=[Depends(auth)])
    def answer(task_id: str):
        raise EngineError("QUESTION_NOT_PENDING")

    @app.get("/v1/tasks/{task_id}/events", dependencies=[Depends(auth)])
    def events(task_id: str, request: Request, last_event_id: int = Header(default=0, ge=0)):
        service = request.app.state.service
        service.view(task_id)

        def stream():
            after = last_event_id
            heartbeat_at = time.monotonic()
            while not service.stopping.is_set():
                for event in service.public_events(task_id, after):
                    after = event["sequence"]
                    yield f"id: {after}\ndata: {canonical(event)}\n\n"
                if service.public_view(task_id)["state"] in TERMINAL:
                    return
                if time.monotonic() - heartbeat_at >= 15:
                    yield ": heartbeat\n\n"
                    heartbeat_at = time.monotonic()
                service.stopping.wait(0.2)

        return StreamingResponse(
            stream(), media_type="text/event-stream", headers={"Cache-Control": "no-store"}
        )

    return app
