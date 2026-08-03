package com.yanban.api.agent.v2.context.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextRevisionDraft;
import com.yanban.api.agent.v2.context.V2ContextRevisionOutcome;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.api.agent.v2.loop.StepModelCallGuard;
import com.yanban.api.agent.v2.loop.StepModelCallGuardException;
import com.yanban.api.agent.v2.loop.V2StepModelCallMaterial;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class V2StepModelCallContextGuardTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void inheritsNineLayersAndReplaysTheSameSafeModelCut() {
        Fixture fixture = fixture(10_000);
        StepModelCallGuard.Call call = new StepModelCallGuard.Call(
                1L, 3L, "activation-1", 7L, 4L,
                material("review current step"));

        fixture.guard().requireReady(call);
        int afterFirst = fixture.revisions().drafts.size();
        fixture.guard().requireReady(call);

        assertThat(fixture.revisions().drafts).hasSize(afterFirst);
        V2ContextRevisionDraft ready = fixture.revisions().drafts.get(
                fixture.revisions().drafts.size() - 1);
        assertThat(ready.status()).isEqualTo(V2ContextRevisionStatus.READY);
        assertThat(ready.stage()).isEqualTo(V2ContextStage.STEP_DECISION);
        assertThat(ready.sections()).hasSize(ContextSectionType.values().length);
        for (ContextSectionType type : ContextSectionType.values()) {
            if (type != ContextSectionType.STEP_STATE
                    && type != ContextSectionType.TOOL_RESULTS) {
                assertThat(section(ready, type)).isSameAs(
                        section(fixture.parent().revision(), type));
            }
        }
        assertThat(ready.sections().stream().filter(section ->
                section.type() == ContextSectionType.STEP_STATE)
                .findFirst().orElseThrow().projectionJson())
                .contains("review current step", "checkpointLastEventSequence");
    }

    @Test
    void oversizedAuthoritativeStepPersistsFailedPhaseAndFailsClosed() {
        Fixture fixture = fixture(8);

        assertThatThrownBy(() -> fixture.guard().requireReady(
                new StepModelCallGuard.Call(1L, 3L,
                        "activation-1", 7L, 4L,
                        material("x".repeat(200)))))
                .isInstanceOf(StepModelCallGuardException.class);

        assertThat(fixture.revisions().drafts)
                .extracting(V2ContextRevisionDraft::status)
                .contains(V2ContextRevisionStatus.ASSEMBLING,
                        V2ContextRevisionStatus.FAILED)
                .doesNotContain(V2ContextRevisionStatus.COMPACTING);
        assertThat(fixture.revisions().drafts.stream()
                .filter(value -> value.stage() == V2ContextStage.STEP_DECISION)
                .noneMatch(value -> value.status()
                        == V2ContextRevisionStatus.READY)).isTrue();
    }

    @Test
    void toolCompactionChangesTheActualRequestAndReplaysItExactly() {
        Fixture fixture = fixture(10_000, 2_000);
        List<V2StepModelCallMaterial.HistoryItem> history = List.of(
                history(1, "drop-marker-" + "x".repeat(1_600)),
                history(2, "keep-marker"));
        StepModelCallGuard.Call call = new StepModelCallGuard.Call(
                1L, 3L, "activation-1", 7L, 4L,
                material("review current step", history));

        ModelRequest first = fixture.guard().requireReady(call);
        int afterFirst = fixture.revisions().drafts.size();
        ModelRequest replay = fixture.guard().requireReady(call);

        assertThat(first.messages().get(1).content())
                .contains("keep-marker").doesNotContain("drop-marker");
        assertThat(replay).isEqualTo(first);
        assertThat(fixture.revisions().drafts).hasSize(afterFirst);
        V2ContextRevisionDraft ready = fixture.revisions().drafts.get(
                fixture.revisions().drafts.size() - 1);
        V2ContextSectionDraft tool = ready.sections().stream()
                .filter(section -> section.type()
                        == ContextSectionType.TOOL_RESULTS)
                .findFirst().orElseThrow();
        assertThat(tool.sourceRefsJson())
                .contains("callDigest", "kept", "removed");
        assertThat(fixture.revisions().drafts)
                .extracting(V2ContextRevisionDraft::status)
                .containsSubsequence(
                        V2ContextRevisionStatus.COMPACTION_REQUIRED,
                        V2ContextRevisionStatus.COMPACTING,
                        V2ContextRevisionStatus.READY);
    }

    @Test
    void infrastructureFailuresAreClassifiedForRecoveryPending() {
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(1L, 3L)).thenThrow(
                new IllegalStateException("database unavailable"));
        V2StepModelCallContextGuard guard = new V2StepModelCallContextGuard(
                contexts, mock(V2ContextRevisionService.class),
                mock(V2ContextRevisionOrchestrator.class),
                mock(V2SectionCompactor.class),
                new V2ContextStageKeyFactory(), json);

        assertThatThrownBy(() -> guard.requireReady(
                new StepModelCallGuard.Call(
                        1L, 3L, "activation-1", 7L, 4L,
                        material("review"))))
                .isInstanceOfSatisfying(
                        StepModelCallGuardException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(
                                        "STEP_CONTEXT_INFRASTRUCTURE_FAILED"));
    }

    @Test
    void requestDigestCoversToolDescriptionCapabilitiesAndSchema() {
        ModelRequest base = material("review").request();
        ToolId id = new ToolId("project.read");
        ToolDescriptor original = new ToolDescriptor(
                id, "read", Set.of(Capability.READ_PROJECT),
                schema(false));
        String digest = V2StepModelCallMaterial.requestDigest(
                withTools(base, original));

        assertThat(V2StepModelCallMaterial.requestDigest(withTools(base,
                new ToolDescriptor(id, "changed",
                        Set.of(Capability.READ_PROJECT), schema(false)))))
                .isNotEqualTo(digest);
        assertThat(V2StepModelCallMaterial.requestDigest(withTools(base,
                new ToolDescriptor(id, "read",
                        Set.of(Capability.ACCESS_NETWORK), schema(false)))))
                .isNotEqualTo(digest);
        assertThat(V2StepModelCallMaterial.requestDigest(withTools(base,
                new ToolDescriptor(id, "read",
                        Set.of(Capability.READ_PROJECT), schema(true)))))
                .isNotEqualTo(digest);
    }

    private Fixture fixture(long stepLimit) {
        return fixture(stepLimit, 10_000);
    }

    private Fixture fixture(long stepLimit, long toolLimit) {
        FakeRevisions fake = new FakeRevisions();
        List<V2ContextSectionDraft> sections = new ArrayList<>();
        for (ContextSectionType type : ContextSectionType.values()) {
            sections.add(section(type, type == ContextSectionType.STEP_STATE
                    ? stepLimit : type == ContextSectionType.TOOL_RESULTS
                    ? toolLimit : 10_000));
        }
        V2ContextRevisionSnapshot parent = fake.service.append(
                new V2ContextRevisionDraft(
                        1L, 2L, 3L, 1, null, null, V2ContextStage.PLANNER,
                        "ctx-v1/planner/parent/initial",
                        V2ContextRevisionStatus.READY,
                        "deepseek", "deepseek-v4-flash", 1_000_000,
                        384_000, "utf8-byte-v1", "layered-v1", 18,
                        50_000, sections));
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(1L, 3L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity("turn", "3", 1L, 2L, null),
                        Optional.empty()));
        V2DefaultSectionCompactor compactor =
                new V2DefaultSectionCompactor(json);
        V2ContextStageKeyFactory keys = new V2ContextStageKeyFactory();
        V2ContextRevisionOrchestrator orchestrator =
                new V2ContextRevisionOrchestrator(fake.service, keys, compactor);
        return new Fixture(new V2StepModelCallContextGuard(
                contexts, fake.service, orchestrator, compactor, keys, json),
                fake, parent);
    }

    private V2StepModelCallMaterial material(String intent) {
        return material(intent, List.of());
    }

    private V2StepModelCallMaterial material(
            String intent,
            List<V2StepModelCallMaterial.HistoryItem> history) {
        V2StepModelCallMaterial.Step step = new V2StepModelCallMaterial.Step(
                "frame-1", "objective", List.of("target"),
                List.of("deliverable"), List.of("constraint"),
                "plan-1", "revision-1", 1L, "step-1", intent,
                "expected", List.of("criterion"), List.of(), 4L, 7L);
        ModelRequest request = new ModelRequest(
                new ModelRequestId("step-request"),
                new CorrelationId("step-correlation"),
                List.of(new ModelMessage(MessageRole.SYSTEM, "system"),
                        new ModelMessage(MessageRole.USER,
                                V2StepModelCallMaterial.userMessage(
                                        step, history))),
                List.of(), new GenerationOptions(
                        256, 0, 0.2, OptionalLong.empty(), Map.of()),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false);
        return new V2StepModelCallMaterial(
                request, V2StepModelCallMaterial.requestDigest(request),
                step, history,
                "a".repeat(64), history.size() + 1L);
    }

    private V2StepModelCallMaterial.HistoryItem history(
            int ordinal, String safeText) {
        return new V2StepModelCallMaterial.HistoryItem(
                ordinal, "call-" + ordinal, "project.read", List.of(),
                Integer.toHexString(ordinal).repeat(64).substring(0, 64),
                true, true, "receipt-" + ordinal, "SUCCESS", "OK", 0,
                "c".repeat(64), false, List.of(), safeText);
    }

    private static ModelRequest withTools(
            ModelRequest value, ToolDescriptor tool) {
        return new ModelRequest(
                value.requestId(), value.correlationId(), value.messages(),
                List.of(tool), value.generationOptions(), value.taskFrameId(),
                value.planId(), value.planRevisionId(), value.stepId(),
                value.cancellationRequested());
    }

    private static ObjectValue schema(boolean additionalProperties) {
        return new ObjectValue(Map.of(
                "type", new TextValue("object"),
                "additionalProperties",
                new BooleanValue(additionalProperties)));
    }

    private V2ContextSectionDraft section(ContextSectionType type, long limit) {
        String projection = "{}";
        long count = projection.getBytes(StandardCharsets.UTF_8).length;
        return new V2ContextSectionDraft(type, type.percentage(), limit,
                count, count, V2ContextSectionStatus.READY,
                "[]", projection, null);
    }

    private static V2ContextSectionDraft section(
            V2ContextRevisionDraft revision, ContextSectionType type) {
        return revision.sections().stream()
                .filter(section -> section.type() == type)
                .findFirst().orElseThrow();
    }

    private record Fixture(V2StepModelCallContextGuard guard,
                           FakeRevisions revisions,
                           V2ContextRevisionSnapshot parent) { }

    private static final class FakeRevisions {
        private final V2ContextRevisionService service =
                mock(V2ContextRevisionService.class);
        private final Map<String, V2ContextRevisionSnapshot> stored =
                new HashMap<>();
        private final Map<Long, V2ContextRevisionSnapshot> storedById =
                new HashMap<>();
        private final List<V2ContextRevisionDraft> drafts = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        private FakeRevisions() {
            when(service.find(any(), any(), any(), any())).thenAnswer(invocation ->
                    Optional.ofNullable(stored.get(invocation.getArgument(3)))
                            .map(value -> replay(value)));
            when(service.findLatest(any(), any(), any())).thenAnswer(invocation ->
                    stored.values().stream().max(java.util.Comparator.comparingInt(
                            value -> value.revision().revisionNumber())));
            when(service.findById(any(), any(), any(), any())).thenAnswer(
                    invocation -> Optional.ofNullable(storedById.get(
                                    invocation.getArgument(3)))
                            .map(value -> replay(value)));
            when(service.append(any())).thenAnswer(invocation -> {
                V2ContextRevisionDraft draft = invocation.getArgument(0);
                drafts.add(draft);
                V2ContextRevisionSnapshot existing = stored.get(
                        draft.stableStageKey());
                if (existing != null) return replay(existing);
                String canonical = draft.toString();
                V2ContextRevisionSnapshot created = new V2ContextRevisionSnapshot(
                        ids.incrementAndGet(), V2ContextRevisionOutcome.APPLIED,
                        draft, canonical, sha256(canonical), List.of());
                stored.put(draft.stableStageKey(), created);
                storedById.put(created.id(), created);
                return created;
            });
        }

        private static V2ContextRevisionSnapshot replay(
                V2ContextRevisionSnapshot value) {
            return new V2ContextRevisionSnapshot(
                    value.id(), V2ContextRevisionOutcome.REPLAYED,
                    value.revision(), value.canonicalJson(),
                    value.contextDigest(), value.projectionDigests());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
