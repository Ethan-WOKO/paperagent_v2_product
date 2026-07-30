# V2 User Journey Stabilization - 2026-07-29

Status: **DELIVERY REPAIRS AND DOCUMENT-ONLY VALIDATION ROUTE REAL-RETESTED**

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
   a separate provider-facing field always names the sole callable alias.
   The internal dotted ToolId is not exposed to the model. The TaskFrame,
   Plan, ToolId, and EffectIntent remain unchanged.
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

After the static review completed, the focused verification command initially
exercised the directly affected test classes and reproduced one pre-existing
adjacent null-effect assertion mismatch at `loop.effect`. The same single test
was reproduced on the unchanged `main` checkout. Its expectation was aligned
with the existing fail-closed production behavior so the focused class can
serve as a valid merge gate.

The final focused gate ran the nine directly affected test classes: 68 tests
passed, with 0 failures, 0 errors, and 0 skipped. `git diff --check` also
passed. No full product, RAG, paper, frontend, deployment, or real-provider
suite was run.

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

Completed against the merged product `main` on 2026-07-29. The focused real
retest used one newly imported, non-sensitive synthetic Project containing four
tracked Markdown samples. The settings page showed DeepSeek as the default
provider, `deepseek-v4-pro` as the default model, and a configured owner key.
No credential, endpoint, Project content, prompt body, provider body, local
path, or raw log is recorded here.

An initial attempt was discarded as product evidence because the already
running backend predated the provider-alias merge. The backend was rebuilt and
restarted from merge commit
`c6ed3cf873c8987f634d0f2d75646c0278260eb0`; the runtime adapter artifact was
then verified to contain the shared provider-safe alias implementation before
the two requests below were repeated.

### Read-analysis result

- The provider-backed Step succeeded.
- Provider-facing `project_read` mapped back to the internal
  `project.read` ToolId.
- One `project.read` EffectIntent and one successful EffectOutcome Receipt
  were persisted under the expected authority ownership.
- The Plan reached successful final synthesis and an assistant message was
  durably bound.
- The frontend nevertheless received a failure because reading the newly
  stored final synthesis raised the repository's integrity failure.
- Recomputing the repository's documented canonical representation from the
  exact persisted fields did not match the stored canonical digest. No source
  field or generated text is included in this report.

This is a result-persistence round-trip defect after successful governed tool
execution. It is not another tool-name alias failure.

### Candidate result

- The `project.read` and `project.candidate.compose` Steps each persisted one
  governed EffectIntent and one successful EffectOutcome Receipt.
- The isolated Workspace contained the proposed modification and the Plan
  reached the post-effect publication boundary.
- Candidate publication then failed with a sanitized
  `WorkspaceException`.
- Static inspection confirms that publication constructs a new
  `LocalWorkspaceProvider` and calls `inspectMaterialization`. Recovery verifies
  the on-disk data as though it were still the original materialization. A
  legitimately modified Candidate Workspace therefore fails that baseline
  verification before the existing Candidate artifact can be stored.
- No Candidate artifact or assistant handoff was created.
- The original Project version and target-file hash remained unchanged. The UI
  continued to show zero reviewed changes and one Project version.

Sandbox validation was not attempted because no Candidate reached the existing
review flow. The E2B Broker remained running, but this focused request did not
invoke it.

## Delivery repairs and focused verification

Both approved delivery repairs were implemented without changing V2 core
contracts or the legacy Agent orchestration:

1. Final synthesis now uses one microsecond-precision persistence-canonical
   `observedAt` value for hashing, writing, returning, reading, and replay.
2. Candidate composition now durably stores the exact validated replacement
   set, its canonical digest, and its diff fingerprint. Publication restores
   those durable facts and revalidates the owner, turn, exact path set,
   ProjectVersion, original file hashes, UTF-8 and size bounds, MODIFY-only
   semantics, and diff fingerprint. It no longer reopens a modified Workspace
   through the pristine-materialization recovery path.

Focused verification after main-agent review:

- `ProductFinalSynthesisRepositoryH2Test`: 4 tests passed.
- Candidate Effect, delivery persistence, service, and V59 migration tests:
  20 tests passed.
- Authenticated direct Project Effect execution: 9 tests passed.
- Total: 33 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` passed.

No full backend, paper, RAG, frontend, deployment, or unrelated test suite was
run.

## Focused real retest after the delivery repairs

The repaired backend was rebuilt and restarted with the V59 migration. A fresh
Project session reused the same non-sensitive four-Markdown-file Project and
the settings-page default DeepSeek provider/model and configured owner key.
No credential, endpoint, raw provider body, raw runtime log, or user file is
recorded here.

### Read-analysis repair result

- The request completed with `SUCCEEDED`.
- The answer was visible in the Project conversation and the V2 result panel.
- It used only the requested `research-plan.md` evidence and returned the four
  requested plan items plus the configured `max_steps` facts.
- The Project remained at the same version.
- The earlier verified `project_read` provider alias to internal
  `project.read` mapping and permission chain were not changed by this repair.

### Candidate repair result

- The request completed with `SUCCEEDED`.
- Candidate artifact 35 opened in the existing Changes review flow.
- It contained exactly one MODIFY replacement for `research-plan.md`.
- Structural, ProjectVersion, evidence, content-hash, and budget validation
  all passed.
- State remained `VALIDATED / NOT_APPLIED`.
- The Project remained at version 1, and the target file retained its original
  hash. `Apply selected changes` remained disabled pending sandbox approval.

### E2B sandbox follow-up

The approved Candidate was sent to the running E2B provider using the UI's
available `MAVEN_TEST` profile. The Broker returned a durable receipt from
provider `e2b`, exit code 1, without timeout or output truncation. The Candidate
remained `NOT_APPLIED` and the original Project remained unchanged.

This synthetic Project contains only Markdown files and no Maven build. The
most direct explanation is therefore a validation-profile mismatch: the
product offered a Maven test for a document-only Project. Raw sandbox output
was intentionally not inspected, so this explanation is recorded as an
inference rather than a proven command-level root cause.

## Document-only validation repair

The approved follow-up keeps documents out of E2B:

- A document-only Project now offers `DOCUMENT_INTEGRITY`, not Maven.
- The page explicitly states that no document is executed and E2B is not
  invoked.
- The backend rechecks Project ownership, current ProjectVersion, Candidate
  validity, exact selected indexes, document-only paths, Candidate fingerprint,
  and the durable request/policy digests.
- A successful local validation is stored as an auditable terminal record with
  no exit code, provider, Broker receipt, stdout, or stderr.
- The application gate accepts that record only when its exact ProjectVersion,
  Candidate fingerprint, selection, policy digest, and request digest still
  match.
- Maven validation is rejected before Workspace materialization or Broker
  dispatch when the Project has no root `pom.xml`.
- Code-project profiles and E2B receipt requirements are unchanged.

Focused verification:

- `CandidateSandboxValidationIntegrationTest`: 11 tests passed, 0 failures,
  0 errors, 0 skipped.
- Three focused frontend test files: 9 tests passed.
- Frontend TypeScript/Vue type checking passed.
- `git diff --check` passed.

Real UI verification reused Candidate artifact 35:

- The page showed `Document integrity check (no E2B)` and the explicit local
  verification explanation.
- Confirmation used `Confirm verification`, not a run command.
- `DOCUMENT_INTEGRITY` completed immediately with `SUCCEEDED`.
- The durable result contained no provider, exit code, or receipt digest and
  stated that no code was executed and E2B was not invoked.
- The older failed Maven validation remained visible as historical evidence;
  no new Maven/E2B validation was created.
- The successful local record enabled the existing apply-confirmation button,
  but no apply action was performed.
- The Project remained at version 1 and the target-file hash remained
  unchanged.

The two delivery defects and the document-only profile mismatch are repaired.
Legacy Agent orchestration remains present and unchanged.

## Explicit apply and code-sandbox real verification

The user explicitly approved mutations to disposable test Projects for this
verification. No production or user Project was used.

### Document Candidate application

- Candidate artifact 35 was applied through the UI's explicit confirmation
  dialog.
- The Project gained a new immutable `APPLICATION` revision (revision 23).
- The prior `UPLOAD` revision (revision 22) remained available for rollback.
- The current `research-plan.md` hash changed from the Candidate base hash
  prefix `f8aaf9ad1a...` to the reviewed result hash prefix `b7cdbaa31b...`.
- The current file preview contained the single reviewed marker and retained
  the earlier content.
- The UI showed two Project versions after application. This verifies that
  applying a document Candidate creates a new version instead of overwriting
  the uploaded version.

### Disposable Maven Project and E2B result

A separate non-sensitive Project, `E2B Sandbox Smoke 20260729` (Project 44),
was imported with four files: a root `pom.xml`, a calculator source file, one
JUnit test file, and a README. Candidate artifact 36 proposed exactly two
MODIFY replacements:

- add `Calculator.subtract(int, int)`;
- add one JUnit assertion for `subtract(7, 2) == 5`.

The Candidate passed structural, ProjectVersion, evidence, content-hash, and
budget checks and remained `VALIDATED / NOT_APPLIED`.

The UI then dispatched `MAVEN_TEST` to E2B. The terminal record proves that the
sandbox provider started and executed the Maven command:

- validation: `eb47d3c7-c2cb-4608-ad5d-d496935bd3a6`;
- provider: `e2b`;
- exit code: 1;
- timed out: false;
- output truncated: false;
- a non-empty trusted receipt digest was stored.

The Java tests did not run. Maven failed before compilation because validation
uses offline mode and the fresh sandbox did not already contain the required
Maven plugin artifacts. Network access was correctly disabled, so Maven could
not download them.

This is not a Candidate code failure and not a failure to start E2B. It is a
reproducibility gap between the product's generic `MAVEN_TEST` option and the
dependencies preloaded in the E2B image. Candidate 36 remained unapplied and
Project 44 remained at its original single version.

No repair was made in this pass. The minimum proposed repair is to make the
E2B Maven validation environment self-contained by preloading and pinning the
approved Maven plugins/dependencies in the sandbox template (or by rejecting
the profile before dispatch when that verified cache is unavailable). Runtime
networking and secret injection should remain disabled.

### Controlled Maven dependency preparation follow-up

The user subsequently approved a narrower runtime alternative: create the E2B
sandbox with an exact `repo.maven.apache.org` outbound allowlist, run a fixed
server-owned Maven `go-offline` command, restore and verify deny-all
networking, and only then run the existing offline Maven test. No user-supplied
dependency command or secret injection was allowed.

Focused automated verification passed:

- E2B adapter: 4 tests passed;
- Sandbox Contract: 3 tests passed;
- Sandbox Broker: 28 tests passed, with 1 expected Windows-only conditional
  skip and no failures or errors;
- focused Candidate document-validation UI tests: 2 tests passed;
- frontend TypeScript/Vue type checking passed;
- `git diff --check` passed.

The latest executable Broker was rebuilt and its real E2B health probe passed.
Real UI attempts then established a provider limitation before any Candidate
command ran:

- validation `5b4647fe-4090-4051-bc8c-1bf3929ac051` was rejected in Broker
  phase `CREATE` when both the compatibility internet flag and the exact
  allowlist were supplied;
- validation `cbc19c35-e49a-4ab6-b60f-6858f3af3f82` reproduced the same bounded
  `CREATE / PROVIDER_REJECTED` result after provider diagnostics were made
  non-sensitive;
- validation `b7f716dc-a129-47f8-91b4-df41303d0af6` was still rejected in
  `CREATE` after removing the compatibility flag and supplying only the exact
  allowlist.

All three terminal records identify provider `e2b`, contain durable receipt
digests, have no exit code, and contain no stdout or stderr. This proves that
the provider rejected custom-network sandbox creation; Maven dependency
preparation and Candidate code were never executed.

Candidate 36 remains `VALIDATED / NOT_APPLIED`, `Apply selected changes`
remains disabled, and Project 44 remains at version 1. Continuing would require
either an E2B account/environment that supports the exact outbound policy, a
prebuilt sandbox template containing the pinned dependencies, or an explicitly
approved weaker policy that permits unrestricted internet during the fixed
dependency command. The weaker policy was not applied.

### User-approved temporary dependency networking

The user selected the temporary-network alternative because a permanently
preloaded template cannot cover arbitrary future Java, Python, or frontend
dependencies. The implementation was narrowed to Maven for this pass:

- E2B starts with temporary internet access;
- only `pom.xml` dependency manifests are uploaded while networking is open;
- the dependency command is fixed and server-owned;
- no user or application secrets are injected;
- deny-all networking must be applied and verified before the complete
  Workspace is uploaded;
- the actual Maven test remains offline.

This removed the provider-level custom-allowlist rejection. Validation
`2db62c04-6443-4478-bd9c-bb70a392f816` reached the dependency command, but
Maven could not establish HTTPS because the template's Java runtime reported an
empty trust-anchor set.

The E2B template was rebuilt with explicit CA packages and build-time
truststore checks. A Windows-only UTF-8 build-log failure was also corrected.
Further attempts verified that:

- the rebuilt template was used;
- the runtime truststore existed and contained `trustedCertEntry` records
  before any Project manifest was uploaded;
- the Maven JVM received the fixed, non-sensitive `JAVA_TOOL_OPTIONS`;
- both explicit JKS and explicitly converted PKCS12 truststores still produced
  `InvalidAlgorithmParameterException: the trustAnchors parameter must be
  non-empty`.

The latest validation, `3df20745-f2fe-4d29-abe8-443888cb3417`, therefore
failed during dependency preparation with exit code 1. It was not a provider
creation failure, timeout, output truncation, Candidate compilation failure, or
test failure. Candidate code was never executed. Its durable E2B receipt was
stored, Candidate 36 remains `VALIDATED / NOT_APPLIED`, `Apply selected
changes` remains disabled, and Project 44 remains at version 1.

The next credible repair is no longer a small configuration change: replace
the template's Java distribution with a known-good runtime such as Eclipse
Temurin, then repeat only this Maven Candidate validation. Disabling TLS
verification or using HTTP is explicitly rejected.

### Eclipse Temurin replacement result

The user approved replacing the E2B template's Debian OpenJDK package with an
independent Eclipse Temurin runtime. The rebuilt template now uses pinned
Eclipse Temurin 17.0.20+8, verifies the downloaded archive by SHA-256, fixes
`JAVA_HOME` to `/opt/yanban/temurin-17`, and fails its image build unless:

- the Java vendor is Eclipse Adoptium;
- the runtime `cacerts` contains trusted certificate entries; and
- Maven reports Java 17.

The template build succeeded. The next real E2B run confirmed that this
replacement fixed the original TLS/trust-anchor failure: Maven reached HTTPS
dependency resolution successfully. No TLS verification was disabled.

That run exposed a separate dependency-preparation gap. The subsequent
offline Maven test could not resolve a transitive dependency of
`maven-surefire-plugin:3.5.2` from the local repository. The Candidate was not
created or applied, no Project version was changed, and no Candidate source
code was executed.

The direct conclusion is:

- the requested Java runtime replacement is successful;
- temporary dependency networking and the transition back to deny-all are
  functioning;
- Maven dependency preparation is not yet complete enough to guarantee that
  the later offline test can resolve every plugin transitive dependency.

No further repair was made in this pass. The minimum follow-up is to strengthen
the fixed, server-owned Maven preparation command so it fetches the exact
plugins and transitive dependencies needed by the later offline test, while
still uploading only dependency manifests before networking is disabled.

### Maven offline plugin preparation repair

The user approved the minimum follow-up. The server-owned Maven preparation
profile now runs a bounded sequence of fixed artifact prefetches followed by
the existing pinned `go-offline` goal. The sequence covers the Maven/Surefire
runtime dependencies observed in this focused Java 17/JUnit 5 validation:

- `org.codehaus.plexus:plexus-utils:1.1`;
- `org.apache.maven.surefire:surefire-junit-platform:3.5.2`;
- `org.junit.platform:junit-platform-launcher:1.9.3`;
- `org.junit.platform:junit-platform-launcher:1.11.4`.

These commands remain server-owned. The browser cannot supply or alter them.
Only POM manifests are present while dependency networking is active. The
complete Workspace is uploaded only after deny-all is applied and verified,
and the actual `mvn -o test` remains offline.

Focused automated verification after the final change passed:

- Sandbox Contract: 3 tests, 0 failures, 0 errors, 0 skipped;
- Sandbox Broker: 28 tests, 0 failures, 0 errors, 1 expected Windows
  conditional skip;
- `git diff --check`: passed.

Real E2B evidence for Candidate 37:

- Candidate fingerprint:
  `7044e1d30573f4b20aa111a55ea402d0d6fdcf4179193dcbcd6e41df7b10e4ce`;
- validation `beb56420-610e-4a53-8fe3-c969df77c390` proved that the earlier
  `plexus-utils` gap was closed and then exposed the dynamically selected
  `surefire-junit-platform:3.5.2`;
- validation `6490aacc-249b-4203-9b04-582b56137773` proved that provider was
  present and then exposed its JUnit Platform dependency chain;
- validation `a2597895-f736-4970-8288-4b623fedc931` progressed through Java 17
  main/test compilation and exposed the Project-selected JUnit Platform
  launcher 1.11.4;
- final validation `49386531-6bb4-4baa-bc35-d7354a028bed` succeeded through
  provider `e2b` with exit code 0, no timeout, no output truncation, and
  receipt digest
  `52ed58c8a63f4e57070e41fe6a604e2e21504c818c2e681829517bf637ef6cac`;
- Maven ran 2 tests with 0 failures, 0 errors, and 0 skipped.

After successful validation and explicit apply confirmation, Candidate 37
created immutable Project 44 revision 25. The Project now has two versions:
the original upload revision 24 remains available and revision 25 is current.
The current `pom.xml`, `Calculator.java`, and `CalculatorTest.java` hashes
match the reviewed Candidate replacements, including the Java 17 compiler
configuration, `subtract` implementation, and its test.

Residual scope: this focused repair verifies the pinned Java 17, Surefire
3.5.2, and JUnit 5.11.4 profile exercised by Project 44. It does not claim
generic offline completeness for every possible Maven plugin, Maven version,
or dependency graph.

### Java single-source repair and dependency preparation

Two gaps remained in the single-source Java path: an unused third-party
import did not trigger a bounded model repair, and source code that genuinely
used a third-party library had no dependency-preparation path unless the
Project already supplied a build manifest.

The repair now runs through a persistent Plan and permits at most one repair
attempt, with no recursive retry. The repair is bound to the original
Candidate authority, source fingerprint, Project version, validation result,
and replacement paths. Provider selection uses the user's saved default model
and key. The model may either remove an unused import or return explicit,
strictly validated Maven coordinates for a genuinely used library.

Dependency preparation is coordinates-only and restricted to Maven Central.
No Project files are uploaded while network access is enabled. After
dependency preparation, deny-all networking is applied and verified before
the Project is uploaded and compiled or executed offline.

Focused review verification used:

- `mvn -q -pl yanban-sandbox-contract -Dtest=SandboxCommandProfilesTest test`
  (4 tests);
- `mvn -q -pl yanban-sandbox-broker -am "-Dtest=E2bCommandFactoryTest,SandboxWorkerPolicyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  (3 tests);
- `mvn -q -pl yanban-api -am "-Dtest=ProjectCandidateCompositionEffectTest,ProjectCandidateDeliveryH2Test" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  (13 tests);
- `mvn -q -pl yanban-api -am "-Dtest=CandidateSandboxValidationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  (11 tests); and
- `python -m unittest deploy.sandbox.e2b.test_e2b_provider deploy.sandbox.e2b.test_java_dependency_runner`
  (9 tests).

The 40 focused tests completed with 0 failures, 0 errors, and 0 skipped.
`git diff --check` also passed. A real E2B template build and end-to-end run
were intentionally not performed in this repair pass; they remain for user
testing.

### Project V1/V2 mode separation

The Project page previously mixed ordinary legacy chat, V2 read analysis, and
V2 Candidate controls in one conversation column. This made it unclear which
runtime would receive a request and made the explicit V2 path difficult to
test.

The page now has a visible V1/V2 mode switch that preserves the current
Project and session. V1 retains the existing legacy conversation. V2 hides
the ordinary message composer and exposes only the explicit V2 read-analysis
and Candidate endpoints in a Chinese workbench. The workbench distinguishes
read-only analysis from isolated Candidate generation, reports the frozen
Project version and persistent Plan progress returned by the endpoint, and
links a successful Candidate to the existing diff, sandbox validation, and
explicit new-version confirmation flow. Candidate review, validation output,
and confirmation copy were translated to Chinese.

Focused verification:

- six directly affected frontend Vitest files: 34 tests, 0 failures;
- `pnpm build`: passed (`vue-tsc` and Vite production build);
- `git diff --check`: passed.

Browser visual inspection was attempted but the in-app browser rejected the
localhost navigation. No browser-level acceptance is claimed. A real signed-in
V1/V2 switch and V2 task run remain for user testing.
