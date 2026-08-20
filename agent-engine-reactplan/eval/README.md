# ReAct production-path evaluation

Issue #193 defines 20 cases. The runner logs in through the public product API,
creates an isolated uploaded Project from a local fixture, creates real Project
sessions, submits tasks through `/api/v1/react-agent/**`, consumes SSE, and
scores durable events and Trace facts.

Credentials are runtime-only:

```powershell
$env:PAPERAGENT_EVAL_USERNAME = '...'
$env:PAPERAGENT_EVAL_PASSWORD = '...'
$env:PAPERAGENT_EVAL_PROJECT_SOURCE = 'C:\path\to\fixture'
npm run eval:react
```

To rerun selected task cases against an existing isolated evaluation Project:

```powershell
$env:PAPERAGENT_EVAL_PROJECT_ID = '...'
$env:PAPERAGENT_EVAL_CASES = 'symbol-search,web-search-sources'
npm run eval:react
```

Control cases are explicit so an operator can restart only the Engine process
between `prepare-restart` and `resume-restart`:

```powershell
npm run eval:react:control -- mutation-and-rollback
npm run eval:react:control -- running-cancel
npm run eval:react:control -- queued-cancel
npm run eval:react:control -- sse-resume
npm run eval:react:control -- concurrency
npm run eval:react:control -- prepare-restart
# restart Engine, without resubmitting the task
npm run eval:react:control -- resume-restart
```

Focused browser verification uses an installed Edge/Chrome executable without
downloading a second browser:

```powershell
$env:PAPERAGENT_EVAL_PROJECT_ID = '...'
npm run eval:react:browser
```

Generated reports live under `.eval-results/` and are ignored by Git. The
runner never includes access tokens or passwords in a report. Use a dedicated
local test account: a full run uploads an isolated Project and mutation cases
create immutable revisions in that Project.

## Persistent Plan versus ReAct

Run the same five fixed, read-only tasks through both production paths:

```powershell
$env:PAPERAGENT_EVAL_USERNAME = '...'
$env:PAPERAGENT_EVAL_PASSWORD = '...'
$env:PAPERAGENT_EVAL_PROJECT_ID = '95'
npm run eval:compare
```

Override the case list with `PAPERAGENT_EVAL_COMPARE_CASES`. The comparison
uses identical answer-content checks and reports wall time for both chains.
ReAct token and model/tool-call totals come from its durable Trace. The legacy
Plan chain never aggregated every planner, executor, verifier, reflection, and
final-synthesis model call into one public metric, so its token total is
reported as `null` with an explicit coverage reason instead of a misleading
number.
