"""Independent dev protocol. No compatible production route is exposed accidentally."""

import secrets
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from pydantic import Field

from .contracts import EngineError, StrictModel, Submission
from .runtime import Runtime
from .storage import canonical


class AdvanceRequest(StrictModel):
    expected_sequence: int = Field(ge=1)


def create_app(data_dir: Path, token: str, model=None, memory=None):
    if len(token) < 24:
        raise ValueError("Use a dedicated development service token of at least 24 characters")

    @asynccontextmanager
    async def lifespan(app):
        app.state.runtime = Runtime(data_dir, model=model, memory=memory)
        try:
            yield
        finally:
            app.state.runtime.close()

    app = FastAPI(title="PaperAgent Python development engine", version="0.1.0", lifespan=lifespan)

    @app.middleware("http")
    async def bounded_body(request, call_next):
        # A finite cap also covers chunked bodies; no echo of rejected content.
        size = 0
        chunks = []
        async for chunk in request.stream():
            size += len(chunk)
            if size > 160_000:
                return JSONResponse(status_code=413, content={"code": "REQUEST_TOO_LARGE"})
            chunks.append(chunk)
        request._body = b"".join(chunks)
        return await call_next(request)

    def authorized(authorization: str | None = Header(default=None)):
        supplied = authorization or ""
        if not secrets.compare_digest(supplied.encode(), ("Bearer " + token).encode()):
            raise EngineError("UNAUTHORIZED", 401)

    @app.exception_handler(EngineError)
    async def engine_error(request, exc):
        return JSONResponse(status_code=exc.status, content={"code": exc.code})

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return JSONResponse(status_code=422, content={"code": "INVALID_REQUEST"})

    @app.get("/healthz")
    def health():
        return {"status": "ok", "protocol": "python-dev/1", "production_connected": False}

    auth = [Depends(authorized)]

    @app.post("/dev/v1/tasks", status_code=202, dependencies=auth)
    def submit(body: Submission, request: Request):
        return request.app.state.runtime.submit(body)

    @app.get("/dev/v1/tasks/{task_id}", dependencies=auth)
    def view(task_id: str, request: Request):
        return request.app.state.runtime.view(task_id)

    @app.post("/dev/v1/tasks/{task_id}/advance", dependencies=auth)
    def advance(task_id: str, body: AdvanceRequest, request: Request):
        return request.app.state.runtime.advance(task_id, body.expected_sequence)

    @app.post("/dev/v1/tasks/{task_id}/cancel", dependencies=auth)
    def cancel(task_id: str, request: Request):
        return request.app.state.runtime.cancel(task_id)

    @app.get("/dev/v1/tasks/{task_id}/events", dependencies=auth)
    def events(task_id: str, request: Request, after: int = Query(default=0, ge=0)):
        return {"events": request.app.state.runtime.store.events(task_id, after)}

    @app.get("/dev/v1/tasks/{task_id}/events.sse", dependencies=auth)
    def event_snapshot(
        task_id: str, request: Request, last_event_id: int = Header(default=0, ge=0)
    ):
        rows = request.app.state.runtime.store.events(task_id, last_event_id)
        body = "".join(f"id: {row['sequence']}\ndata: {canonical(row)}\n\n" for row in rows)
        return Response(body, media_type="text/event-stream", headers={"Cache-Control": "no-store"})

    return app
