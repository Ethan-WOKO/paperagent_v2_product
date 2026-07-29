# V2 User Journey Stabilization - 2026-07-29

Status: **IMPLEMENTATION VERIFICATION IN PROGRESS; REAL RETEST PENDING**

## Scope

This report records two defects found by a focused product user-journey
evaluation of the explicit V2 Project analysis and Project Candidate routes:

- the Plan lease timestamp defect fixed by Issues #89 and #90;
- the Plan-owner model-settings defect addressed by Issue #91.

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

## Real product retest

Pending after Issue #91 is reviewed and merged:

1. Re-run only one V2 Project read-analysis request against a synthetic Project.
2. Confirm the provider-backed Step creates and progresses governed read/search
   effects and produces a terminal analysis.
3. Re-run only one V2 Project Candidate prepare request.
4. Confirm the Candidate remains review-only and the original Project version
   is unchanged.
5. Attempt sandbox validation only if Candidate creation succeeds.
