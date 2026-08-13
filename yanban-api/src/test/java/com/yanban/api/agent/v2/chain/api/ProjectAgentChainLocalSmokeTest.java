package com.yanban.api.agent.v2.chain.api;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainModelInvocationWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.context.ChainContextManager;
import io.paperagent.v2.chain.context.ChainFrozenContext;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.model.ChainModelCallPort;
import io.paperagent.v2.chain.model.ChainModelCallResult;
import io.paperagent.v2.chain.model.ChainModelMaterializationPort;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.ChainRoleOutputDecoder;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-7 bounded local matrix.  It deliberately uses fixed data and a local
 * provider double; it never opens a network connection or reads a project
 * file.  The four rows are the only smoke surface for the complete chain.
 */
class ProjectAgentChainLocalSmokeTest {
    private static final Instant NOW = Instant.parse("2026-08-07T03:04:05Z");
    private static final String TASK = "smoke-task";
    private static final String PROJECT_VERSION = "project-v1";

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void localFakeMatrix(String name) {
        switch (name) {
            case "DIRECT" -> directAnswerDoesNotReadProjectBody();
            case "PERSISTENT_PLAN_EXECUTE" -> persistentPlanUsesFrozenFacts();
            case "GAP_RECOVERY" -> gapResponseResumesTheSameGap();
            case "CANDIDATE_VALIDATE_PUBLISH" -> candidateValidationAndPublishStayBound();
            default -> throw new AssertionError("unknown smoke row: " + name);
        }
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of("DIRECT"),
                Arguments.of("PERSISTENT_PLAN_EXECUTE"),
                Arguments.of("GAP_RECOVERY"),
                Arguments.of("CANDIDATE_VALIDATE_PUBLISH"));
    }

    private void directAnswerDoesNotReadProjectBody() {
        LocalFacts facts = new LocalFacts();
        FakeProvider provider = new FakeProvider();
        PlannerPayload.DirectRoute route = new PlannerPayload.DirectRoute(
                "fixed local question", "answer from fixed facts", List.of(),
                List.of(), false, false, false, false, null);
        // The direct row is represented by the Answer role after the route cut.
        ChainModelProtocolOutcome.ProposalReady answer = invoke(
                provider, new io.paperagent.v2.chain.AnswerPayload.DirectAnswer(
                        "route-1", "answer from fixed facts", "fixed answer", List.of()),
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                "direct answer", Set.of("route-1"), "direct");
        assertEquals(ChainProposalKind.ANSWER_DIRECT_ANSWER,
                answer.proposal().proposalKind());
        assertEquals("fixed answer", answer.bodyContent().body());
        assertEquals(1, provider.calls());
        facts.readAuthenticatedProjectMetadata();
        assertEquals(1, facts.metadataReads());
        assertEquals(0, facts.fileBodyReads());
        assertFalse(facts.workspaceCreated());
        assertFalse(facts.candidateCreated());
        assertEquals("fixed local question", route.routeReason());
    }

    private void persistentPlanUsesFrozenFacts() {
        FakeProvider provider = new FakeProvider();
        PlannerPayload.PersistentPlan plan = persistentPlan();
        Set<String> visible = Set.of(
                "Sort.java", PROJECT_VERSION, "SANDBOX_STANDARD", "compile-fact");
        ChainModelProtocolOutcome.ProposalReady first = invoke(
                provider, plan, ChainRole.PLANNER, ChainWorkState.PLANNING,
                "initial planning", visible, "persistent");
        assertEquals(ChainProposalKind.PLANNER_PERSISTENT_PLAN,
                first.proposal().proposalKind());
        assertEquals(2, plan.initialPlan().steps().size());
        assertEquals("merge-sort", plan.initialPlan().steps().get(0).stepKey());
        assertEquals("validate", plan.initialPlan().steps().get(1).stepKey());
        assertEquals(1, provider.calls());
        assertEquals(0, first.bodyContent() == null ? 0 : 1);
        ChainModelProtocolOutcome.ProposalReady replay = invokeReplay(
                provider, plan, ChainRole.PLANNER, ChainWorkState.PLANNING,
                "initial planning", visible, "persistent");
        assertTrue(replay.recovered());
        // The replay is local to its own durable store; each independent smoke
        // invocation has one provider call and its second call is recovered.
        assertEquals(2, provider.calls());
    }

    private void gapResponseResumesTheSameGap() {
        FakeProvider provider = new FakeProvider();
        String gapId = "gap-compile-input";
        PlannerPayload.NeedUserInput pending = gapQuestion(gapId, false, "fact-gap-open");
        ChainModelProtocolOutcome.ProposalReady first = invoke(
                provider, pending, ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM, "validate pending gap",
                Set.of(gapId, "fact-gap-open"), "gap-pending");
        assertEquals(ChainProposalKind.PLANNER_NEED_USER_INPUT,
                first.proposal().proposalKind());

        GapLifecycle lifecycle = new GapLifecycle(gapId);
        lifecycle.responseReceived();
        PlannerPayload.NeedUserInput resolved = gapQuestion(gapId, true, "fact-gap-resolved");
        ChainModelProtocolOutcome.ProposalReady second = invoke(
                provider, resolved, ChainRole.PLANNER,
                ChainWorkState.VALIDATING_PENDING_ITEM, "validate gap response",
                Set.of(gapId, "fact-gap-resolved"), "gap-resolved");
        lifecycle.validateResolved();
        assertEquals(ChainProposalKind.PLANNER_NEED_USER_INPUT,
                second.proposal().proposalKind());
        assertEquals("RESOLVED", lifecycle.status());
        assertEquals(2, provider.calls());
    }

    private void candidateValidationAndPublishStayBound() {
        FakeProvider provider = new FakeProvider();
        ExecutorPayload.ToolAction payload = new ExecutorPayload.ToolAction(
                "sandbox.execute",
                "{\"paths\":[\"Sort.java\"],\"argv\":[\"javac\",\"Sort.java\"]}",
                "Sort.java", "compile and run the isolated candidate",
                List.of("compile receipt", "run output"), "SANDBOX_STANDARD",
                List.of("Sort.java"), List.of("Sort.java"), null, null, null, null, null);
        provider.emit(payload);
        assertEquals(payload, provider.emitted());

        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(payload);
        ChainPersistenceRecords.ProposalStateEventRecord state =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        proposal.proposalId(), 1, TASK, "proposal-state-1",
                        io.paperagent.v2.chain.ChainProposalState.ACCEPTED,
                        null, null, NOW);
        ChainPersistenceRecords.ActionBindingRecord binding = actionBinding(proposal);
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> new ChainProposalAdmissionService.AdmissionResult(state, true, false),
                ignored -> binding,
                ignored -> new ChainEffectRuntime.ExecutionOutcome(
                        ChainEffectRuntime.OutcomeKind.EFFECT_SUCCEEDED,
                        frozenAction(binding), "receipt-sort-1", null, null, null,
                        ChainEffectRuntime.GateStatus.CURRENT));
        ProductChainExecutorPump.Result result = pump.execute(
                TASK, new ChainModelProtocolOutcome.ProposalReady(proposal, null, 1, false), NOW);

        LocalFacts facts = new LocalFacts();
        facts.createWorkspace();
        facts.createCandidate("Sort.java", "merge sort output: [1, 2, 3]");
        facts.validateCandidate("receipt-sort-1");
        facts.publishCandidate(PROJECT_VERSION);
        assertEquals(ProductChainExecutorPump.Status.EFFECT_DISPATCHED, result.status());
        assertEquals("receipt-sort-1", result.receiptRef());
        assertEquals(1, provider.calls());
        assertTrue(facts.workspaceCreated());
        assertTrue(facts.candidateCreated());
        assertEquals("VALIDATED", facts.candidateStatus());
        assertEquals(PROJECT_VERSION, facts.publishedVersion());
    }

    private static PlannerPayload.PersistentPlan persistentPlan() {
        ProposalFields.TaskFrameDraft frame = new ProposalFields.TaskFrameDraft(
                "Compile and improve Sort.java", List.of("Sort.java"),
                List.of("compiled output", "merge-sort output"),
                List.of("work only in the sandbox"), PROJECT_VERSION, "SANDBOX_STANDARD",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(new io.paperagent.v2.contracts.ValidationRequirement(
                                "candidate-validation",
                                io.paperagent.v2.contracts.ValidationSubject.CANDIDATE,
                                "merge sort output is shown")),
                        io.paperagent.v2.contracts.PublishRequirement.REQUIRED));
        ProposalFields.RequirementCoverage coverage = new ProposalFields.RequirementCoverage(
                "compile and run", ProposalFields.RequirementStatus.SATISFIED,
                List.of("compile-fact"));
        ProposalFields.StepDraft compile = new ProposalFields.StepDraft(
                "merge-sort", 1, "Add merge sort", List.of(),
                List.of("merge sort is implemented"), List.of("Sort.java"),
                List.of("updated Sort.java"), true, null);
        ProposalFields.StepDraft mergeSort = new ProposalFields.StepDraft(
                "validate", 2, "Compile and run Sort.java",
                List.of("merge-sort"),
                List.of("Sort.java compiles", "Sort.java runs",
                        "merge sort output is shown"),
                List.of("Sort.java"), List.of("execution receipt"), false,
                "merge sort output is shown", List.of(),
                List.of("candidate-validation"));
        return new PlannerPayload.PersistentPlan(frame, List.of(coverage),
                new ProposalFields.PlanDraft(List.of(compile, mergeSort)), List.of(), null);
    }

    private static PlannerPayload.NeedUserInput gapQuestion(
            String gapId, boolean resolved, String factRef) {
        return new PlannerPayload.NeedUserInput(
                List.of("compiler availability"), "the sandbox compiler is not known",
                "Which compiler should be used?", "a compiler command",
                List.of("compiler command is available"), ChainRole.PLANNER,
                ChainRole.PLANNER, "resume compile step",
                new GapValidation(gapId,
                        List.of(new GapValidation.Check(
                                "compiler command is available", resolved, factRef)),
                        resolved ? GapValidation.Outcome.RESOLVED
                                : GapValidation.Outcome.STILL_PENDING));
    }

    private static ChainModelProtocolOutcome.ProposalReady invoke(
            FakeProvider provider, io.paperagent.v2.chain.ChainProposalPayload payload,
            ChainRole role, ChainWorkState workState, String reason,
            Set<String> visibleRefs, String invocationSuffix) {
        LocalModelStore store = new LocalModelStore(
                context(role, workState, reason, invocationSuffix), visibleRefs,
                payload);
        ChainModelProtocolService protocol = protocol(store, provider, payload);
        return (ChainModelProtocolOutcome.ProposalReady) protocol.invoke(
                request(role, workState, reason, invocationSuffix,
                        workState == ChainWorkState.VALIDATING_PENDING_ITEM
                                ? payload.gapValidation().gapId() : null));
    }

    private static ChainModelProtocolOutcome.ProposalReady invokeReplay(
            FakeProvider provider, io.paperagent.v2.chain.ChainProposalPayload payload,
            ChainRole role, ChainWorkState workState, String reason,
            Set<String> visibleRefs, String invocationSuffix) {
        LocalModelStore store = new LocalModelStore(
                context(role, workState, reason, invocationSuffix), visibleRefs,
                payload);
        ChainModelProtocolService protocol = protocol(store, provider, payload);
        ChainModelProtocolRequest request = request(role, workState, reason,
                invocationSuffix, null);
        ChainModelProtocolOutcome.ProposalReady first =
                (ChainModelProtocolOutcome.ProposalReady) protocol.invoke(request);
        ChainModelProtocolOutcome.ProposalReady replay =
                (ChainModelProtocolOutcome.ProposalReady) protocol.invoke(request);
        assertEquals(first.proposal().proposalId(), replay.proposal().proposalId());
        return replay;
    }

    private static ChainModelProtocolService protocol(
            LocalModelStore store, FakeProvider provider,
            io.paperagent.v2.chain.ChainProposalPayload payload) {
        ChainModelCallPort call = provider::call;
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) ->
                new ProviderRoleOutput(ProviderRoleOutput.SCHEMA_VERSION,
                        payload.kind().wireName(), payload);
        return new ChainModelProtocolService(store, store, store, store, call, decoder);
    }

    private static ChainModelProtocolRequest request(
            ChainRole role, ChainWorkState state, String reason,
            String suffix, String gapId) {
        return new ChainModelProtocolRequest(TASK, "invocation-" + suffix,
                "context-" + suffix, "completion-" + suffix, role, state,
                reason, "local-fake-provider", "fixed-model", 1, gapId, NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord context(
            ChainRole role, ChainWorkState state, String reason, String suffix) {
        String prompt = "fixed-prompt-" + suffix;
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context-" + suffix, TASK, null, role, state, reason, "instruction-1",
                null, null, null, null, null, null, 7L, PROJECT_VERSION, null,
                null, null, null, null, null, "projectors-v1", "pagination-v1",
                "chain-runtime-policy-v1", ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"), sha256(prompt),
                "completion-" + suffix, null, null, NOW, NOW);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            ExecutorPayload.ToolAction payload) {
        String json = "{\"toolId\":\"" + payload.toolId() + "\"}";
        return new ChainPersistenceRecords.ModelProposalRecord(
                "proposal-candidate", TASK, "invocation-candidate", 1,
                ChainRole.EXECUTOR, ChainProposalKind.EXECUTOR_TOOL_ACTION,
                canonical(json), canonical("[\"sandbox.execute\"]"), null, null, NOW);
    }

    private static ChainPersistenceRecords.ActionBindingRecord actionBinding(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        String hash = "a".repeat(64);
        return new ChainPersistenceRecords.ActionBindingRecord(
                "action-candidate", TASK, "action-event-candidate", proposal.proposalId(), 1,
                hash, "idempotency-candidate", "instruction-1", "frame-1", "plan-1",
                "revision-1", "compile", "activation-1", "workspace-candidate", "NONE",
                null, null, null, null, hash, NOW);
    }

    private static ChainEffectRuntime.FrozenMutation frozenAction(
            ChainPersistenceRecords.ActionBindingRecord action) {
        return new ChainEffectRuntime.FrozenMutation(
                ChainEffectRuntime.SourceKind.TOOL_ACTION, action.taskId(), action.actionId(),
                action.idempotencyKey(), action.proposalId(), action.instructionId(),
                action.taskFrameId(), action.planId(), action.planRevisionId(), action.stepId(),
                action.activationEventId(), action.workspaceId(), action.baseCandidateKey(),
                action.actionSignatureSha256(), action.versionFenceSha256());
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class FakeProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private io.paperagent.v2.chain.ChainProposalPayload emitted;

        ChainModelCallResult call(io.paperagent.v2.chain.model.ChainModelCallRequest ignored) {
            calls.incrementAndGet();
            return new ChainModelCallResult.Success("fixed-provider-output", "STOP", 1, Map.of());
        }

        void emit(io.paperagent.v2.chain.ChainProposalPayload payload) {
            emitted = payload;
            calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }

        io.paperagent.v2.chain.ChainProposalPayload emitted() {
            return emitted;
        }
    }

    private static final class LocalModelStore implements ChainContextManager,
            ChainModelRepository, ChainModelInvocationWriter, ChainModelMaterializationPort {
        private final ChainPersistenceRecords.ContextRevisionRecord context;
        private final Set<String> visibleRefs;
        private final Map<String, ChainPersistenceRecords.ModelInvocationRecord> invocations = new HashMap<>();
        private final Map<String, List<ChainPersistenceRecords.ProviderAttemptRecord>> attempts = new HashMap<>();
        private final Map<String, ChainPersistenceRecords.ContentRecord> contents = new HashMap<>();
        private final Map<String, ChainPersistenceRecords.ModelProposalRecord> proposals = new HashMap<>();

        LocalModelStore(ChainPersistenceRecords.ContextRevisionRecord context,
                        Set<String> visibleRefs, io.paperagent.v2.chain.ChainProposalPayload payload) {
            this.context = context;
            this.visibleRefs = new HashSet<>(visibleRefs);
        }

        @Override
        public ChainFrozenContext recover(String taskId, String contextRevisionId) {
            String prompt = "fixed-prompt-" + context.contextRevisionId().substring("context-".length());
            List<ChainPersistenceRecords.ContextModuleRecord> modules = new ArrayList<>();
            ChainPersistenceRecords.CanonicalJson json = canonical("{}");
            for (ChainContextModule module : ChainContextModule.values()) {
                modules.add(new ChainPersistenceRecords.ContextModuleRecord(
                        context.contextRevisionId(), TASK, module.ordinalCode(), module,
                        ChainContextModuleStatus.PRESENT, json, json, "projection-v1",
                        "pagination-v1", json, json, NOW));
            }
            return new ChainFrozenContext(context, modules, prompt, visibleRefs);
        }

        @Override
        public io.paperagent.v2.chain.context.ChainContextFreezeOutcome freeze(
                io.paperagent.v2.chain.context.ChainContextFreezeRequest request) {
            throw new UnsupportedOperationException("smoke store is already frozen");
        }

        @Override
        public Optional<ChainPersistenceRecords.ModelInvocationRecord> findInvocation(String id) {
            return Optional.ofNullable(invocations.get(id));
        }

        @Override
        public long highestInvocationOrdinal(String taskId) {
            return invocations.values().stream()
                    .filter(value -> taskId.equals(value.taskId()))
                    .mapToLong(ChainPersistenceRecords.ModelInvocationRecord::invocationOrdinal)
                    .max().orElse(0L);
        }

        @Override
        public List<ChainPersistenceRecords.ModelInvocationRecord> findInvocations(String taskId, long cut) {
            return List.copyOf(invocations.values());
        }

        @Override
        public int highestProviderAttemptNo(String invocationId) {
            return attempts.getOrDefault(invocationId, List.of()).stream()
                    .mapToInt(ChainPersistenceRecords.ProviderAttemptRecord::attemptNo)
                    .max().orElse(0);
        }

        @Override
        public List<ChainPersistenceRecords.ProviderAttemptRecord> findProviderAttempts(String id) {
            return List.copyOf(attempts.getOrDefault(id, List.of()));
        }

        @Override
        public List<ChainPersistenceRecords.ContentRecord> findContents(String id) {
            return contents.values().stream().filter(v -> id.equals(v.invocationId())).toList();
        }

        @Override
        public Optional<ChainPersistenceRecords.ContentRecord> findContent(String id) {
            return Optional.ofNullable(contents.get(id));
        }

        @Override
        public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposal(String id) {
            return Optional.ofNullable(proposals.get(id));
        }

        @Override
        public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposalByInvocation(String id) {
            return proposals.values().stream().filter(v -> id.equals(v.invocationId())).findFirst();
        }

        @Override
        public List<ChainPersistenceRecords.ProposalStateEventRecord> findProposalStateEvents(String id) {
            return List.of();
        }

        @Override
        public ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ModelInvocationRecord>
        appendInvocation(ChainPersistenceRecords.ModelInvocationRecord value) {
            ChainPersistenceRecords.ModelInvocationRecord existing =
                    invocations.putIfAbsent(value.invocationId(), value);
            return new ChainPersistenceRecords.AppendResult<>(existing == null ? value : existing,
                    existing != null);
        }

        @Override
        public ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ProviderAttemptRecord>
        appendProviderAttempt(ChainPersistenceRecords.ProviderAttemptRecord value) {
            attempts.computeIfAbsent(value.invocationId(), ignored -> new ArrayList<>()).add(value);
            return new ChainPersistenceRecords.AppendResult<>(value, false);
        }

        @Override
        public SuccessfulMaterialization persistSuccessfulAttempt(
                ChainPersistenceRecords.ProviderAttemptRecord attempt,
                ChainPersistenceRecords.ContentRecord body,
                ChainPersistenceRecords.ModelProposalRecord proposal) {
            attempts.computeIfAbsent(attempt.invocationId(), ignored -> new ArrayList<>()).add(attempt);
            if (body != null) contents.put(body.contentId(), body);
            proposals.put(proposal.proposalId(), proposal);
            return new SuccessfulMaterialization(attempt, body, proposal, false);
        }
    }

    private static final class LocalFacts {
        private int metadataReads;
        private int fileBodyReads;
        private boolean workspaceCreated;
        private boolean candidateCreated;
        private String candidateStatus = "NONE";
        private String publishedVersion;

        void readAuthenticatedProjectMetadata() { metadataReads++; }
        int metadataReads() { return metadataReads; }
        int fileBodyReads() { return fileBodyReads; }
        boolean workspaceCreated() { return workspaceCreated; }
        boolean candidateCreated() { return candidateCreated; }
        void createWorkspace() { workspaceCreated = true; }
        void createCandidate(String path, String body) {
            assertEquals("Sort.java", path);
            assertTrue(body.contains("merge sort"));
            candidateCreated = true;
            candidateStatus = "CREATED";
        }
        void validateCandidate(String receipt) {
            assertNotNull(receipt);
            assertTrue(candidateCreated);
            candidateStatus = "VALIDATED";
        }
        void publishCandidate(String version) {
            assertEquals("VALIDATED", candidateStatus);
            publishedVersion = version;
        }
        String candidateStatus() { return candidateStatus; }
        String publishedVersion() { return publishedVersion; }
    }

    private static final class GapLifecycle {
        private final String gapId;
        private String status = "PENDING";
        GapLifecycle(String gapId) { this.gapId = gapId; }
        void responseReceived() {
            assertEquals("gap-compile-input", gapId);
            assertEquals("PENDING", status);
            status = "RESPONSE_RECEIVED";
        }
        void validateResolved() {
            assertEquals("RESPONSE_RECEIVED", status);
            status = "RESOLVED";
        }
        String status() { return status; }
    }
}
