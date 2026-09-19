"""Local metadata, append-only events and replay journal. No product database access."""

import hashlib
import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from threading import RLock

from .contracts import EngineError


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, path):
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.lock = RLock()
        self.connection.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL,
                digest TEXT NOT NULL, request TEXT NOT NULL,
                status TEXT NOT NULL, error TEXT, created TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (
                task_id TEXT NOT NULL, sequence INTEGER NOT NULL,
                event_key TEXT NOT NULL, body TEXT NOT NULL,
                PRIMARY KEY(task_id, sequence), UNIQUE(task_id, event_key));
            CREATE TABLE IF NOT EXISTS operations (
                task_id TEXT NOT NULL, key TEXT NOT NULL, digest TEXT NOT NULL,
                kind TEXT NOT NULL, result TEXT,
                PRIMARY KEY(task_id, key));
        """)

    @contextmanager
    def transaction(self):
        with self.lock, self.connection:
            yield self.connection

    def task(self, task_id):
        with self.lock:
            row = self.connection.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if row is None:
            raise EngineError("TASK_NOT_FOUND", 404)
        return dict(row)

    def submit(self, request):
        data = request.model_dump(mode="json")
        fingerprint = digest(data)
        task_id = "pydev_" + digest(request.client_request_id)[:32]
        with self.transaction() as db:
            row = db.execute("SELECT digest FROM tasks WHERE id=?", (task_id,)).fetchone()
            if row:
                if row[0] != fingerprint:
                    raise EngineError("REQUEST_CONFLICT")
                return task_id, True
            db.execute(
                "INSERT INTO tasks VALUES (?,?,?,?,?,?,?)",
                (
                    task_id,
                    request.client_request_id,
                    fingerprint,
                    canonical(data),
                    "queued",
                    None,
                    now(),
                ),
            )
            self.event(task_id, "accepted", "status", {"status": "queued"}, db)
        return task_id, False

    def event(self, task_id, key, kind, payload, db):
        if db.execute(
            "SELECT 1 FROM events WHERE task_id=? AND event_key=?", (task_id, key)
        ).fetchone():
            return
        seq = db.execute(
            "SELECT COALESCE(MAX(sequence),0)+1 FROM events WHERE task_id=?", (task_id,)
        ).fetchone()[0]
        body = {
            "protocol": "python-dev/1",
            "task_id": task_id,
            "sequence": seq,
            "type": kind,
            "occurred_at": now(),
            **payload,
        }
        db.execute("INSERT INTO events VALUES (?,?,?,?)", (task_id, seq, key, canonical(body)))

    def events(self, task_id, after=0):
        self.task(task_id)
        with self.lock:
            rows = self.connection.execute(
                "SELECT body FROM events WHERE task_id=? AND sequence>? ORDER BY sequence",
                (task_id, after),
            ).fetchall()
        return [json.loads(row[0]) for row in rows]

    def terminal(self, task_id, status, code=None):
        with self.transaction() as db:
            current = self.task(task_id)
            if current["status"] in {"succeeded", "failed", "cancelled"}:
                return
            db.execute("UPDATE tasks SET status=?,error=? WHERE id=?", (status, code, task_id))
            self.event(task_id, "terminal", "status", {"status": status, "error": code}, db)

    def operation(self, task_id, key, kind, payload, invoke):
        """Reserve before calling. Uncertain calls fail closed after process loss."""
        fingerprint = digest(payload)
        with self.transaction() as db:
            row = db.execute(
                "SELECT * FROM operations WHERE task_id=? AND key=?", (task_id, key)
            ).fetchone()
            if row:
                if row["digest"] != fingerprint or row["kind"] != kind:
                    raise EngineError("OPERATION_CONFLICT")
                if row["result"] is None:
                    raise EngineError("OPERATION_OUTCOME_UNKNOWN")
                return json.loads(row["result"])
            if self.task(task_id)["status"] == "cancelled":
                raise EngineError("TASK_CANCELLED")
            count = db.execute(
                "SELECT COUNT(*) FROM operations WHERE task_id=? AND kind=?", (task_id, kind)
            ).fetchone()[0]
            if count >= (20 if kind == "model" else 24):
                raise EngineError("BUDGET_EXCEEDED")
            db.execute(
                "INSERT INTO operations VALUES (?,?,?,?,NULL)", (task_id, key, fingerprint, kind)
            )
        result = invoke()
        with self.transaction() as db:
            db.execute(
                "UPDATE operations SET result=? WHERE task_id=? AND key=?",
                (canonical(result), task_id, key),
            )
        return result

    def close(self):
        self.connection.close()
