# V2 User Journey Stabilization - 2026-07-29

Status: **FOLLOW-UP REPAIR UNDER STATIC REVIEW; REAL RETEST PENDING**

## Scope

This report records two defects found by a focused product user-journey
evaluation of the explicit V2 Project analysis and Project Candidate routes:

- the Plan lease timestamp defect fixed by Issues #89 and #90;
- the Plan-owner model-settings defect addressed by Issues #91 and #92;
- an OpenAI-compatible tool-name and request-endpoint integration defect now
  handled as an ordinary repair without a separate Issue;
- a stale-request recovery defect observed during negative-path testing and
  repaired at the backend request boundary;
- four additional integration defects found by the requested full-chain static
  review and repaired without separate Issues.

The evidence below is intentionally sanitized. It contains no credentials,
endpoint values, prompts, Project content, user files, local paths, or raw
provider messages.

## Issue #89: persisted Plan lease timestamp

### Symptom

Both explicit V2 Project routes failed before useful Step execution. Ordinary
product model calls remained available.

### Evidence

- The durable delivery and Plan bootstrap existed.
- A Plan lease row was acquired.
- The first execution-start authority was not committed in the original
  failing cut.
- The failure occurred before Project effects, Workspace changes, Candidate
  publication, or sandbox execution.

### Root cause

The product constructed a lease expiry with nanosecond precision, while the
relational persistence boundary retained microsecond precision. A subsequent
exact authority comparison treated the same logical expiry as different and
rejected the start.

### Fix

Issues #89 and #90 canonicalized delivery-owned lease authority timestamps to
microsecond precision before they enter fresh-start, execution-context, and
loop attempts. No V2 core contract or schema was changed.

### Verification

Focused lease-authority service tests and the affected API compile gate passed
before merge. A later product retest progressed beyond execution start into
execution-context creation, Workspace materialization, and active-Step
recovery, confirming that the original timestamp gate was cleared.

## Issue #91: authenticated owner model settings

### Symptom

After the user's model settings were corrected, ordinary chat and ordinary
Project Agent calls succeeded, but the explicit V2 Project analysis and
Candidate routes still failed at the provider-backed loop stage. Neither route
created its first governed Project EffectIntent.

### Evidence

- Execution-start, execution-context, Workspace, and first-Step activation
  authorities were present.
- The persistent loop failed at its kernel/provider boundary.
- The product V2 model adapter built the synchronous product `ChatRequest`
  without the authenticated owner's key or optional API URL.
- The ordinary product path resolved the current user's settings successfully.

### Root cause

The V2 adapter had no authoritative mapping from `ModelRequest.planId` to a
product user. It therefore could not call the mature user-settings resolver and
sent null endpoint credentials. A server-pinned provider/model would also be
incorrect product behavior because the settings page defines the user's
default provider and default model.

### Fix

Issue #91 adds a fail-closed product-side resolver:

1. Treat `ModelRequest.planId` as untrusted input.
2. Query the three durable V2 delivery tables and require exactly one owning
   `user_id` across literature, Project analysis, and Project Candidate.
3. Reject zero, multiple, or cross-delivery owner rows before provider
   invocation.
4. Call the existing
   `UserSettingsService.resolveModelEndpoint(userId, null, null)` so the
   settings-page default provider, default model, matching key, and optional
   API URL remain authoritative.
5. Copy that endpoint only into the one in-memory synchronous `ChatRequest`.

The key and optional URL are not added to V2 contracts, TaskFrames, Plans,
events, checkpoints, intents, receipts, database rows, logs, or exception
messages. The transient endpoint's string representation is redacted.

Project failure diagnostics are also narrowed to a stable loop stage and Java
failure type. They do not include exception messages, causes, model data, or
Project data.

## Focused verification for Issue #91

The focused gate covers:

- adapter forwarding of the resolved default provider, default model, key, and
  optional URL into one synchronous `ChatRequest`;
- sanitized endpoint, resolver-failure, provider-failure, and malformed-result
  strings;
- missing and ambiguous Plan-owner rejection before settings or provider use;
- the exact `resolveModelEndpoint(userId, null, null)` call;
- existing provider response, ToolCall mapping, and Project Step selection;
- sanitized Project analysis and Candidate loop-stage diagnostics;
- `yanban-api` compilation with directly required modules;
- diff whitespace and repository scope/security audits.

Full paper, RAG, literature-retrieval, frontend, deployment, and unrelated
product suites are outside this defect's focused boundary.

Verification result:

- `ProductChatModelProviderAdapterTest`: 4 tests passed.
- `ProductStepTurnConfigurationTest`: 2 tests passed.
- `PlanOwnerModelEndpointResolverTest`: 3 tests passed.
- `AuthenticatedAgentTurnStepTurnVerticalTest`: 1 test passed.
- `V2ProjectAnalysisServiceTest`: 7 tests passed.
- `V2ProjectCandidateServiceTest`: 7 tests passed.
- Total: 24 tests, 0 failures, 0 errors, 0 skipped.
- The affected `yanban-api` reactor compile and diff whitespace check passed.

## Follow-up real product retest after Issue #92

### Evidence

The same two explicit V2 Project routes were retried after the authenticated
owner's settings endpoint reached the V2 `ChatRequest`.

- Both routes progressed through execution start, execution-context creation,
  Workspace materialization, and first-Step activation.
- Both routes failed at `loop.kernel`.
- Neither route persisted an `EffectIntent`.

This proves the earlier lease fix remained effective and narrows the next
failure to the shared model-turn boundary. It does not, by itself, prove which
provider-side validation failed.

### Root cause found by static review

The V2 tool identifiers contain dots, including `project.read`,
`project.search`, `literature.search`, and
`project.candidate.compose`. The product adapter forwarded those identifiers
unchanged as OpenAI-compatible function names. That provider-facing field
accepts a narrower name alphabet than the V2 `ToolId` contract.

The same review found a second concrete defect: both synchronous and streaming
DeepSeek calls always selected the server property URL, even when the
authenticated settings resolver supplied a request-specific optional API URL.

Finally, the product loop reduced every typed single-turn kernel protocol
failure to the single string `kernel`. Stable kernel stage, code, and path were
available but discarded, which made this failure unnecessarily hard to
localize.

### Minimal repair under review

- At the product provider boundary only, map characters outside
  `[A-Za-z0-9_-]` to `_` before sending a tool name.
- Keep an in-memory reverse map for that one request and restore the original
  V2 `ToolId` before constructing the proposed tool call.
- Reject overlong names, alias collisions, and provider-returned names that
  were not offered. Do not change core ToolIds, Plans, or EffectIntents.
- Make DeepSeek synchronous and streaming calls prefer a nonblank
  `ChatRequest.apiUrl`, retaining the configured property as fallback.
- Preserve the kernel's stable enum stage, enum code, and allowlisted path in
  the product loop exception. Do not retain exception messages, provider
  response bodies, credentials, endpoint values, prompts, or Project content.

### Focused checks executed before the static-review pause

- `ProductChatModelProviderAdapterTest`: 5 tests passed.
- `DeepSeekModelProviderTest`: 7 tests passed.
- Two kernel diagnostic tests and the adjacent analysis/Candidate diagnostic
  tests: 4 tests passed.

One broader class-level loop command also exercised 26 tests and exposed an
existing mismatch in the unrelated null-effect test path, plus two assertions
that still expected the old coarse diagnostic. It is not counted as a passing
gate. The diagnostic compatibility assertions were corrected and the four
directly affected tests then passed. No additional tests or real product
retests will run until the requested full-chain static review is complete.

## Full-chain static-review follow-up

Status: **IMPLEMENTED BUT NOT TESTED, COMPILED, OR RETESTED**

The requested static review found four concrete integration defects. Each was
handled as a minimal repair in this stabilization change:

1. The deterministic Step-turn prompt still named the authoritative dotted
   `ToolId`, while the provider tool declaration used its safe underscore
   alias. A single shared boundary alias function is now used by both paths.
   Only the provider-facing copy of the exact selected-tool directive is
   translated when that fixed directive occurs exactly once. Arbitrary,
   missing, or repeated natural-language intent remains valid and unchanged;
   a separate provider-facing field always names the sole callable alias and
   marks the dotted ToolId as internal identity only. The TaskFrame, Plan,
   ToolId, and EffectIntent remain unchanged.
2. Project Candidate composition created a `ModelRequest` without recovered
   TaskFrame, Plan revision, or Step authority. The authenticated effect
   composer now passes the exact recovered ACTIVE authority into Candidate
   composition, which rejects mismatched Plan or Step identity.
3. Explicitly invalid analysis and Candidate normalization/manifest requests
   did not consistently return HTTP 400. They now do. A missing analysis
   request now returns HTTP 404 so the client can clear stale scoped request
   state.
4. Custom provider resolution did not honor the settings-page default provider
   when both request arguments were absent. Custom resolution now uses the
   saved default provider, prefers an exact provider/model pair, otherwise
   selects the stable first model for that provider, and only falls back by
   owner-qualified model when an explicit model has no provider match.
   Built-in provider behavior is unchanged, and custom provider identity is
   not lowercased.

At the requested static-review checkpoint, tests were added or adjusted for
these boundaries but no tests, compilation, formatter checks, or real product
retest had run.

After the static review completed, the focused verification command ran only
the nine directly affected test classes. It exercised 68 tests: 67 passed and
one pre-existing adjacent null-effect test errored at `loop.effect`. That known
test-path mismatch was recorded before these four edits and was not widened
into this repair. The newly added kernel diagnostic test was then run alone and
passed. No full product suite was run.

## Stale-request recovery defect

During a negative-path request using an invalid Project path, the page retained
stale request state instead of cleanly recovering the current scoped request
after reload. This is a presentation/recovery defect; it did not create an
EffectIntent, Candidate, Project mutation, or sandbox execution.

The backend recovery boundary now maps a missing scoped analysis request to
HTTP 404 and explicit invalid analysis input to HTTP 400. The frontend itself
was not changed. This repair is implemented but remains unverified.

## Deferred higher-risk hardening

The following concerns were identified but intentionally left out because they
need separate design and broader risk review:

- precise per-tool parameter schemas instead of the current coarse declaration;
- provider capability negotiation for tool calling;
- outbound allowlisting and SSRF controls for user-configured API URLs;
- structured prompt encoding and adversarial prompt-injection coverage.

These are not represented as completed repairs in this report.

## Next real product retest

Pending after the follow-up repair is reviewed and verified:

1. Re-run only one V2 Project read-analysis request against a synthetic Project.
2. Confirm the provider-backed Step creates and progresses governed read/search
   effects and produces a terminal analysis.
3. Re-run only one V2 Project Candidate prepare request.
4. Confirm the Candidate remains review-only and the original Project version
   is unchanged.
5. Attempt sandbox validation only if Candidate creation succeeds.
