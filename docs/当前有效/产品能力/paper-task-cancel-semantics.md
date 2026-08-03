# Paper Task Cancel Semantics

## Scope

This note records the first safe-stop baseline for paper tasks. It aligns the existing stop endpoint with the roadmap requirement that stopping a task is a cooperative cancellation flow, not a hard thread kill and not a fake terminal `STOPPED` state.

This change does not introduce Kafka, a new worker runtime, a literature task queue, or the full Agent tool manifest. Those belong to later roadmap issues.

## State Flow

For a non-terminal task:

```text
RUNNING / PAUSED / WAITING_INPUT
  -> stop requested by user
  -> CANCEL_REQUESTED
  -> worker reaches a checkpoint
  -> CANCELLING
  -> CANCELLED
```

If the task is no longer running when the stop request is accepted, the service may move it directly to `CANCELLED`.

Terminal states are idempotent for stop requests:

```text
COMPLETED / FAILED / CANCELLED / legacy STOPPED
  -> stop requested by user
  -> unchanged
```

## Events

The SSE stream uses explicit cancellation events so the frontend can show "stopping" instead of misreading the task as failed:

- `cancel_requested`: the backend accepted the stop request.
- `cancelling`: the worker reached a safe checkpoint and is stopping cooperatively.
- `cancelled`: the task is terminally cancelled.

The old `STOPPED` status is treated as a legacy terminal state. New stop requests should not create fresh `STOPPED` tasks.

## Worker Rules

Cancellation is cooperative. The worker checks cancellation before and after expensive or externally visible stages:

- before storage/model/retrieval calls;
- after parser, retrieval, analysis, polish, and assemble calls return;
- before publishing final completion.

If a cancellation request arrives while an external call is in progress, the backend waits for the call or its timeout to return, then writes `CANCELLED` at the next checkpoint.
