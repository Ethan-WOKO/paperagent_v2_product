# Verification and handoff

Branch: `codex/issue-185-react-optimization`. Issues: #216, #217, #218, #219.
Executed on Windows / Java 17 / Node 24, 2026-09-09 through 2026-09-10.

Implemented: private declarative Skill installation/management; existing paper polishing via workspace
and Project tools; current-user conversation search/detail; bounded admin conversation scrolling.
**Draft / partial acceptance**: do not merge based on this report alone. Full ReAct cancellation stability
and real-model/multi-service acceptance remain open. Redis optimization is not part of this change.

## Scope and evidence

No `agent-v2` core, broker protocol, existing Flyway migration, .env, credentials or user files changed.
Tests use H2 and mocks; browser acceptance intercepts every API request in isolated contexts.
Existing MySQL/Redis/Kafka/MinIO/Elasticsearch ports were listening. Backend and Engine were not running;
the temporary Vite server was started only for browser acceptance. Listening ports are not an end-to-end health proof.

### Java

From repository root:

```powershell
mvn -q -pl yanban-api -am "-Dtest=SkillUploadParserTest,UserSkillsServiceTest,UserSkillsPersistenceTest,SkillsUploadControllerTest,SkillsControllerIntegrationTest,PaperPolishStartServiceTest,PaperPolishInputResolverTest,PaperPolishStartToolExecutorTest,PaperTaskToolExecutorTest,PaperTaskServiceTest,PaperOrchestratorCancellationTest,PastConversationHistoryRepositoryTest,PastConversationHistoryServiceTest,PastConversationToolExecutorTest,AgentToolPolicyEngineTest,AgentStrategySelectorTest,LangChain4jToolCallingStrategyTest,UserCapabilityToolProviderTest,AgentEngineRegisteredToolGatewayTest,ReactPlanTaskSkillPolicyTest,ControlledWorkerToolProviderIntegrationTest,AgentRuntimeCoordinatorTest,AgentServiceRuntimeAssemblyTest,ReactPlanConversationHistoryServiceTest,MemoryDistillationModelExtractorTest,MemoryDistillationTransactionsTest,MemoryDistillationWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Completed initial integrated pass: **274 tests, 0 failures, 0 errors, 0 skipped**, across 27 suites (API 258, paper 16).
Final retry-scope refinements were rerun with the same integrated command above, adding
`,PlanAgentServiceTest#retryableRepairContextIsPassedToTheSecondBoundedStepAttempt` to `-Dtest`.
At 00:28 on September 10: **277 tests, 0 failures, 0 errors, 0 skipped**, 28 suites (API 261, paper 16).
This covers only the directly changed legacy retry identity; it does not claim the entire legacy suite is green.

Earlier expanded diagnostic command (not the final focused pass):

```powershell
mvn -q -pl yanban-api -am "-Dtest=PlanAgentServiceTest,AgentServiceRuntimeAssemblyTest,AgentRuntimeCoordinatorTest,AgentStrategySelectorTest,UserCapabilityToolProviderTest,PaperPolishStartServiceTest,LangChain4jToolCallingStrategyTest,AgentEngineRegisteredToolGatewayTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

That expanded run had **176 tests, 7 failures, 6 errors**; all 13 were in legacy PlanAgentServiceTest.
HEAD source inspection showed old planner-overload mock/verify signatures already differ from production.
No separate baseline execution was performed, so this is source-based diagnosis, not a proven baseline run.
Following the user's scope correction, no old planner mock repair was made. The one directly changed retry
test passes in the final integrated run. The current Project page remains on ReAct Engine.

Final entry-point/Skill-contract follow-up, completed 00:40 after the last production changes:

```powershell
mvn -q -pl yanban-api -am "-Dtest=SkillUploadParserTest,UserSkillsServiceTest,SkillsUploadControllerTest,AgentServicePaperRoutingTest,AgentServiceRuntimeAssemblyTest,ConversationIntentRouterServiceTest,AgentStrategySelectorTest,UserCapabilityToolProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

**85 tests, 0 failures, 0 errors, 0 skipped**, 8 suites. Counts overlap earlier runs and must not be
added as unique tests. Includes 5 public sendMessage tests with real navigation router/tool policy:
direct paper request, ordinary attachment with conditional paper guidance, mixed paper/literature,
empty Skill tools and unchanged explicit literature shortcut. Parser tests now number 36, including
32,000/32,001 code points, supplementary characters, full frontmatter/BOM/CRLF, independent UTF-8
byte bounds and exact ReAct tool-name schema. No Engine schema expansion.

Coverage includes owned installation lifecycle, parser/body limits, hostile YAML, builtin compatibility,
database persistence and user isolation, full Spring context/Flyway through V103, source file access/hash checks,
paper task concurrent creation/rollback/replay/dispatch claim, request authority injection and rejection,
production descriptors through both gateways, history literal match/assistant-only delivery/long-message pagination,
deleted/archive/foreign session rejection, frozen Skill enforcement, and existing memory distillation behavior.

During validation, fixed new HQL TRIM-on-CLOB incompatibility without truncating text; moved bounded blank filtering
to the safe projection and used an owner-qualified native existence query for deduplication. Fixed test stub setup
that triggered an earlier stub and configured the new persistence slice with H2 MySQL mode.
The production legacy Plan change is limited to stable plan/step invocation identity; unrelated mocks remain untouched.

### Engine

From `agent-engine-reactplan`:

```powershell
npm run typecheck
npm test
```

Typecheck passed, including the final 00:27 rerun. The 00:17 full run passed **79/79**, 5 files.
Final full reruns are **not all green**: 00:27 had **78 passed / 1 failed**, the existing hard-cancel test
timed out at 5 seconds; 00:28 had **78 passed / 1 failed**, concurrent cancellation hit Windows EPERM
renaming a temporary task file to task.json in unchanged TaskStore.save. The hard-cancel test passed in
that latter run. These are intermittent observations, not proof of their root cause or production impact.
Do not replace this evidence with the earlier successful run or mark full regression complete.

Final focused capability check at 00:29:

```powershell
npx vitest run test/engine.test.ts -t "forged builtin|bounds paper|another paper|literature"
```

**8 passed / 54 not selected**, covering new Skill/paper behavior and existing literature boundaries.
New tests cover forged builtin invocation under an empty Skill, per-target bounded paper polling, WAITING_INPUT,
terminal status, and two paper IDs queried in one batch. Existing literature polling tests still pass.
No unrelated cancellation/TaskStore behavior or timeout was changed. Full-suite stability is an open
acceptance item, not silently waived as environmental; investigate separately before marking the PR ready.

### Frontend

From `frontend` (Vite at 127.0.0.1:5173 for browser tests):

```powershell
npm run build
npx vitest run tests/skillsApi.test.ts tests/skillManagement.test.ts
npx vitest run tests/paperPolishInput.test.ts tests/memoryGovernance.test.ts tests/memoryApi.test.ts tests/memoryI18n.test.ts
npm run test:markdown
node tests/adminLayout.browser.mjs
node tests/skillsManagement.browser.mjs
```

- Build/TypeScript passed; existing large-bundle warning remains (JS chunk exceeds 500 kB).
- Final Skill API/component: **24/24**; paper input + existing memory: **17/17**; markdown: **6/6**.
  Combined final Vitest command (00:38, 41 tests):
  `npx vitest run tests/skillsApi.test.ts tests/skillManagement.test.ts tests/paperPolishInput.test.ts tests/memoryGovernance.test.ts tests/memoryApi.test.ts tests/memoryI18n.test.ts`.
  Production build and both browser scripts were rerun successfully after the final Skill changes.
- Admin: **2 viewport scenarios** (1440px and 390px), 100 sessions with 300-line messages,
  bounded scrolling, keyboard focus, invitation position stability/access, no horizontal overflow.
- Skills: **8 scenario groups** across desktop/mobile: install both files, reload, toggle,
  explicit uninstall confirmation, failure recovery, builtin protection, upload/error messages,
  safe plaintext rendering, and general settings Save preserving latest disabled Skills.
- Browser API fixtures do not exercise the real model, storage or database.

## Unrun checks and residual boundaries

- Full repository Maven suite not run; focused reactor scope covers changed modules and direct runtime boundaries.
- ReAct full regression still has intermittent cancellation failures described above; required acceptance is partial.
- Real MySQL Flyway application, real-model tool selection, MinIO upload-to-polished-result execution and
  live multi-service/two-page acceptance remain unrun. No personal credential/account was used and no production
  data was seeded/modified for acceptance. H2/mocks do not prove those end-to-end paths.
- Skills accept declarative UTF-8 SKILL.md and optional skill.yaml only, not arbitrary folders, archives,
  scripts or dependencies. Full prompt is capped at 32,000 Unicode code points and 64 KiB;
  allowed tool names match the existing ReAct snake_case schema, at most 64 ASCII characters each.
  Supported metadata/default tools are displayed on the settings page.
- Paper input reuses full `.tex` and optional `.bib` only, maximum 1 MiB each. One start per request/Plan step;
  changed arguments in the same scope conflict. A new user request can deliberately create another task.
  READY dispatch can be retried against the same binding; process death after durable STARTED claim has no
  automatic lease takeover. The existing task page provides status/control; do not claim automatic crash recovery.
- HTTP replay protection requires the same clientRequestId; absent keys use persisted turn identity.
  Caller identity always comes from authentication, never this request key or model arguments.
- History is literal substring retrieval, not semantic/vector search. Each page scans at most 100 rows and
  returns at most 10 items; empty pages with hasMore must continue. Snippets are 400 characters and detail
  segments 4,000. Stored LOB reads are not byte-capped and cross-page concurrent snapshots are not promised.
- Native personal-tool routing keeps simple answers to one model call but adds tool-schema context tokens.
  Tools remain optional and permitted side effects still require the current user request; Skill text is not authority.
- Redis optimization is intentionally deferred. Measure list/history/status latency and freshness requirements
  before introducing owner-qualified caches and explicit invalidation; do not cache authorization as a substitute
  for current database ownership checks.

## Restart and manual acceptance

1. Start/restart backend after the normal database backup; Flyway adds V102/V103 tables. Do not edit old migrations.
2. Rebuild/restart ReAct Engine (`npm start` includes the existing prestart build). Frontend production requires rebuild/redeploy; development uses Vite reload.
   Broker code is unchanged and does not need a rebuild/restart solely for this feature.
3. Settings: install SKILL.md, inspect tools, disable/enable, reload; select it separately in workspace/Project.
4. Workspace: upload a full .tex (optional .bib) and explicitly request polishing with zh/en; open returned paper task.
   Project: select a .tex and use the helper button, inspect/edit the composed request and send it. Check task status,
   WAITING_INPUT actions and artifacts; original Project files/version must remain unchanged.
5. Ask for a distinctive past discussion from workspace and Project; verify source references and relevant details.
   A second user must not see the first user's Skill, source paper or conversations.
6. Admin: expand many/long visitor conversations, scroll inside the region and operate invitation controls.

Application rollback may leave the additive new tables in place. No old user rows are rewritten by migrations.
Feature commits and shared integration have dependencies: review/revert the whole bundle together unless the
corresponding shared registrations and tests are also adjusted. Never force-reset the long-lived development branch.
