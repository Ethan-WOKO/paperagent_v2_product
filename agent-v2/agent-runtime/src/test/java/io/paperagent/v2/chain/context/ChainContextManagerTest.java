package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainContextManagerTest {
    @Test
    void freezesAllRolesThroughOneOrderedThirteenModuleEntry() {
        for (ChainRole role : ChainRole.values()) {
            var store = new ChainContextTestFixtures.MemoryContextStore();
            var source = ChainContextTestFixtures.source(role.name());
            var manager = manager(store, source, "token-" + role.name());

            var outcome = manager.freeze(new ChainContextFreezeRequest(
                    ChainContextTestFixtures.building("revision-" + role.name(), role),
                    100_000));

            var complete = assertInstanceOf(ChainContextFreezeOutcome.Complete.class, outcome);
            assertEquals(ChainContextRevisionStatus.COMPLETE,
                    complete.context().revision().status());
            assertEquals(13, complete.context().modules().size());
            assertEquals(ChainContextInputMatrix.orderedModules(), complete.context().modules()
                    .stream().map(module -> module.module()).toList());
            assertNotNull(complete.context().revision().requestDigest());
            assertEquals("token-" + role.name(), complete.context().revision().completionToken());
            assertNotEquals(complete.context().revision().requestManifest().json(),
                    complete.context().canonicalPrompt());
            assertEquals(ChainContextDigests.sha256(complete.context().canonicalPrompt()),
                    complete.context().revision().requestDigest());
            assertTrue(complete.context().canonicalPrompt()
                    .contains(role.name() + "-projection"));
            assertFalse(complete.context().revision().requestManifest().json()
                    .contains(role.name() + "-projection"));
            assertEquals(java.util.Set.of("authority." + role.name()),
                    complete.context().visibleSourceRefs());
            assertEquals(1, store.completes());
            assertEquals(0, store.blocks());
        }
    }

    @Test
    void failsClosedAsInputBlockedWithoutTruncatingAnyModule() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, ChainContextTestFixtures.source("oversize"), "unused");

        var outcome = manager.freeze(new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-blocked", ChainRole.PLANNER),
                13));

        var blocked = assertInstanceOf(ChainContextFreezeOutcome.InputBlocked.class, outcome);
        assertEquals(DefaultChainContextManager.INPUT_BLOCKED_ERROR_CODE, blocked.errorCode());
        assertEquals(ChainContextRevisionStatus.INPUT_BLOCKED,
                blocked.context().revision().status());
        assertEquals(13, blocked.context().modules().size());
        assertEquals(1, store.blocks());
        assertEquals(0, store.completes());
    }

    @Test
    void commitsAndReplaysTypedBuildFailureWithoutReprojecting() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        AtomicInteger projections = new AtomicInteger();
        ChainContextSource source = request -> {
            projections.incrementAndGet();
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                    ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                    ChainContextException.FailureDisposition
                            .FORMAL_BUILD_BLOCK,
                    "safe diagnostic is not authority");
        };
        var manager = manager(store, source, "unused");
        var request = new ChainContextFreezeRequest(
                ChainContextTestFixtures.building(
                        "revision-build-blocked", ChainRole.EXECUTOR),
                100_000);

        var first = assertInstanceOf(
                ChainContextFreezeOutcome.BuildBlocked.class,
                manager.freeze(request));
        var replay = assertInstanceOf(
                ChainContextFreezeOutcome.BuildBlocked.class,
                manager.freeze(request));

        assertEquals(1, projections.get());
        assertEquals(first.failure(), replay.failure());
        assertEquals(ChainContextRevisionStatus.BUILDING,
                first.buildingRevision().status());
        assertEquals(ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                first.failure().failedModule());
        assertEquals(DefaultChainContextManager.INPUT_BLOCKED_ERROR_CODE,
                first.failure().errorCode());
        assertFalse(first.failure().toString().contains(
                "safe diagnostic is not authority"));
        assertEquals(0, store.completes());
        assertEquals(0, store.blocks());
    }

    @Test
    void doesNotFormalizeUntypedOrCorruptionContextExceptions() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, request -> {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_DIGEST_MISMATCH,
                    "corrupt module");
        }, "unused");

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> manager.freeze(new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building(
                                "revision-corrupt", ChainRole.REFLECTOR),
                        100_000)));

        assertEquals(ChainContextErrorCode.CONTEXT_MODULE_DIGEST_MISMATCH,
                failure.code());
        assertTrue(store.findContextBuildFailure(
                "revision-corrupt").isEmpty());
    }

    @Test
    void doesNotFormalizeLegacyInputBlockedWithoutExplicitDisposition() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, request -> {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                    ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                    "identity conflict must propagate");
        }, "unused");

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> manager.freeze(new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building(
                                "revision-legacy-blocked", ChainRole.EXECUTOR),
                        100_000)));

        assertEquals(ChainContextException.FailureDisposition.PROPAGATE,
                failure.failureDisposition());
        assertTrue(store.findContextBuildFailure(
                "revision-legacy-blocked").isEmpty());
    }

    @Test
    void rejectsMissingModuleAndMissingMinimumFieldsBeforeTerminalCas() {
        var missingStore = new ChainContextTestFixtures.MemoryContextStore();
        var missingManager = manager(missingStore,
                ChainContextTestFixtures.source("missing")
                        .omit(ChainContextModule.CONVERSATION_CONTEXT), "unused");
        ChainContextException missing = assertThrows(ChainContextException.class,
                () -> missingManager.freeze(new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building("revision-missing", ChainRole.PLANNER),
                        100_000)));
        assertEquals(ChainContextErrorCode.CONTEXT_SOURCE_MODULE_SET_INVALID, missing.code());
        assertEquals(0, missingStore.completes());

        var fieldsStore = new ChainContextTestFixtures.MemoryContextStore();
        var fieldsManager = manager(fieldsStore,
                ChainContextTestFixtures.source("fields")
                        .insufficient(ChainContextModule.PROJECT_AND_INPUT_MATERIALS), "unused");
        ChainContextException fields = assertThrows(ChainContextException.class,
                () -> fieldsManager.freeze(new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building("revision-fields", ChainRole.EXECUTOR),
                        100_000)));
        assertEquals(ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING, fields.code());
        assertEquals(0, fieldsStore.completes());
    }

    @Test
    void targetWorkspaceWithoutCandidateMayFreezeTheExplicitEmptyModule() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, ChainContextTestFixtures.source("workspace-target")
                .empty(ChainContextModule.WORKSPACE_AND_CANDIDATE), "token-workspace");

        var outcome = manager.freeze(new ChainContextFreezeRequest(
                withWorkspaceCandidate("workspace.target", null, null),
                100_000));

        var complete = assertInstanceOf(
                ChainContextFreezeOutcome.Complete.class, outcome);
        assertEquals(ChainContextRevisionStatus.COMPLETE,
                complete.context().revision().status());
        assertEquals(ChainContextModuleStatus.EMPTY,
                complete.context().modules().stream()
                        .filter(module -> module.module()
                                == ChainContextModule.WORKSPACE_AND_CANDIDATE)
                        .findFirst().orElseThrow().presenceKind());
    }

    @Test
    void formalCandidateStillRequiresTheWorkspaceCandidateModuleToBePresent() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, ChainContextTestFixtures.source("candidate-required")
                .empty(ChainContextModule.WORKSPACE_AND_CANDIDATE), "unused");

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> manager.freeze(new ChainContextFreezeRequest(
                        withWorkspaceCandidate("workspace.target", 91L,
                                "a".repeat(64)), 100_000)));

        assertEquals(ChainContextErrorCode.CONTEXT_REQUIRED_MODULE_EMPTY,
                failure.code());
    }

    @Test
    void rejectsAnActualMissingProjectionFieldInEveryRoleModuleCell() {
        for (ChainContextInputMatrix.Entry entry : ChainContextInputMatrix.entries()) {
            var store = new ChainContextTestFixtures.MemoryContextStore();
            var source = ChainContextTestFixtures.source("matrix-" + entry.role() + "-" + entry.module())
                    .insufficient(entry.module());
            var manager = manager(store, source, "unused");
            ChainContextException failure = assertThrows(ChainContextException.class,
                    () -> manager.freeze(new ChainContextFreezeRequest(
                            ChainContextTestFixtures.building(
                                    "revision-" + entry.role() + "-" + entry.module(), entry.role()),
                            1_000_000)));
            assertEquals(ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    failure.code(), entry.role() + "/" + entry.module());
        }
    }

    @Test
    void fullCanonicalPromptRatherThanProjectionBodiesControlsTheInputLimit() {
        var sizingSource = ChainContextTestFixtures.source("sizing");
        var building = ChainContextTestFixtures.building("revision-size-probe", ChainRole.PLANNER);
        int projectionCharacters = sizingSource.project(
                new ChainContextProjectionRequest(building, 1_000_000)).stream()
                .mapToInt(snapshot -> snapshot.projection().json().length()).sum();
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, ChainContextTestFixtures.source("sizing"), "unused");

        var outcome = manager.freeze(new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-full-manifest-limit", ChainRole.PLANNER),
                projectionCharacters + 1));

        var blocked = assertInstanceOf(ChainContextFreezeOutcome.InputBlocked.class, outcome);
        assertTrue(blocked.inputCharacters() > projectionCharacters);
        assertNotEquals(blocked.context().canonicalPrompt(),
                blocked.context().revision().requestManifest().json());
        assertEquals(ChainContextDigests.sha256(blocked.context().canonicalPrompt()),
                blocked.context().revision().inputDigest());
    }

    private static DefaultChainContextManager manager(
            ChainContextTestFixtures.MemoryContextStore store,
            ChainContextSource source,
            String token) {
        return new DefaultChainContextManager(
                store,
                store,
                source,
                Clock.fixed(Instant.parse("2026-08-07T01:03:00Z"), ZoneOffset.UTC),
                () -> token);
    }

    private static io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord
            withWorkspaceCandidate(
                    String workspaceId,
                    Long candidateArtifactId,
                    String candidateFingerprint) {
        var source = ChainContextTestFixtures.building(
                "revision-workspace-" + (candidateArtifactId == null
                        ? "empty" : "candidate"), ChainRole.EXECUTOR);
        return new io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord(
                source.contextRevisionId(), source.taskId(),
                source.parentContextRevisionId(), source.role(),
                source.workState(), source.callReason(), source.instructionId(),
                source.taskFrameId(), source.planId(), source.planRevisionId(),
                source.planRevisionNumber(), source.stepId(),
                source.activationEventId(), source.projectId(),
                source.projectVersion(), workspaceId, candidateArtifactId,
                candidateFingerprint, source.validationId(),
                source.validationRequestDigest(), source.validationReceiptDigest(),
                source.projectorSetVersion(), source.paginationVersion(),
                source.runtimePolicyVersion(), source.status(),
                source.moduleCount(), source.requestManifest(),
                source.requestDigest(), source.completionToken(),
                source.blockedErrorCode(), source.inputDigest(),
                source.createdAt(), source.completedAt());
    }
}
