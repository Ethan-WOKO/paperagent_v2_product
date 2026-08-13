package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainContextRevisionRecoveryTest {
    @Test
    void recoveryUsesOnlyFrozenProjectionAndNeverReprojectsLatestSources() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var source = ChainContextTestFixtures.source("historical");
        var manager = manager(store, source);
        var outcome = (ChainContextFreezeOutcome.Complete) manager.freeze(
                new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building("revision-history", ChainRole.PLANNER),
                        100_000));
        String frozenProjection = outcome.context().modules().get(0).projection().json();
        source.marker("new-authority-state");

        ChainFrozenContext recovered = manager.recover("task-1", "revision-history");

        assertEquals(frozenProjection, recovered.modules().get(0).projection().json());
        assertEquals(outcome.context().canonicalPrompt(), recovered.canonicalPrompt());
        assertEquals(ChainContextDigests.sha256(recovered.canonicalPrompt()),
                recovered.revision().requestDigest());
        assertEquals(1, source.calls(), "historical recovery must not call authority projectors");
    }

    @Test
    void replayOfCompleteRevisionReusesTokenAndDoesNotReproject() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var source = ChainContextTestFixtures.source("retry");
        var manager = manager(store, source);
        var request = new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-retry", ChainRole.EXECUTOR),
                100_000);
        var first = (ChainContextFreezeOutcome.Complete) manager.freeze(request);
        source.marker("must-not-be-read");

        var replay = (ChainContextFreezeOutcome.Complete) manager.freeze(request);

        assertEquals(first.context().revision().completionToken(),
                replay.context().revision().completionToken());
        assertEquals(first.context().revision().requestDigest(),
                replay.context().revision().requestDigest());
        assertEquals(1, source.calls());
        assertEquals(1, store.completes());
    }

    @Test
    void recoveryRejectsBuildingCrossTaskAndProjectionDigestCorruption() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var source = ChainContextTestFixtures.source("recovery-gates");
        var manager = manager(store, source);
        var building = ChainContextTestFixtures.building("revision-building", ChainRole.ANSWER);
        store.createContextRevision(building);

        ChainContextException unfinished = assertThrows(ChainContextException.class,
                () -> manager.recover("task-1", "revision-building"));
        assertEquals(ChainContextErrorCode.CONTEXT_REVISION_NOT_RECOVERABLE, unfinished.code());

        manager.freeze(new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-corrupt", ChainRole.REFLECTOR),
                100_000));
        ChainContextException crossTask = assertThrows(ChainContextException.class,
                () -> manager.recover("other-task", "revision-corrupt"));
        assertEquals(ChainContextErrorCode.CONTEXT_REVISION_TASK_MISMATCH, crossTask.code());

        ContextModuleRecord original = store.findContextModules("revision-corrupt").get(0);
        CanonicalJson corrupted = new CanonicalJson(
                1, original.projection().sha256(), "{\"corrupted\":true}");
        store.replaceModule(new ContextModuleRecord(
                original.contextRevisionId(), original.taskId(), original.moduleOrdinal(),
                original.module(), original.presenceKind(), original.sourceVersion(),
                original.readBoundary(), original.projectionVersion(), original.paginationVersion(),
                original.projectionParameters(), corrupted, original.createdAt()));

        ChainContextException digest = assertThrows(ChainContextException.class,
                () -> manager.recover("task-1", "revision-corrupt"));
        assertEquals(ChainContextErrorCode.CONTEXT_MODULE_DIGEST_MISMATCH, digest.code());
    }

    @Test
    void newFactsRequireANewChildRevisionWhileParentRemainsImmutable() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var source = ChainContextTestFixtures.source("parent-cut");
        var manager = manager(store, source);
        var parent = (ChainContextFreezeOutcome.Complete) manager.freeze(
                new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building("revision-parent", ChainRole.PLANNER),
                        100_000));
        source.marker("child-cut");
        var child = (ChainContextFreezeOutcome.Complete) manager.freeze(
                new ChainContextFreezeRequest(
                        ChainContextTestFixtures.building(
                                "revision-child", ChainRole.PLANNER, "revision-parent"),
                        100_000));

        assertTrue(manager.recover("task-1", "revision-parent").modules().get(0)
                .projection().json().contains("parent-cut-projection"));
        assertTrue(child.context().modules().get(0).projection().json()
                .contains("child-cut-projection"));
        assertEquals(ChainContextRevisionStatus.COMPLETE, parent.context().revision().status());
    }

    @Test
    void resumesAnInterruptedBuildingRevisionWithoutReplacingFrozenModules() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        store.failAfterModuleAppends(3);
        var source = ChainContextTestFixtures.source("partial-stable");
        var manager = manager(store, source);
        var request = new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-partial-stable", ChainRole.EXECUTOR),
                100_000);

        assertThrows(IllegalStateException.class, () -> manager.freeze(request));
        assertEquals(3, store.findContextModules("revision-partial-stable").size());

        var completed = (ChainContextFreezeOutcome.Complete) manager.freeze(request);

        assertEquals(13, completed.context().modules().size());
        assertEquals(2, source.calls());
        assertEquals(1, store.completes());
    }

    @Test
    void rejectsAnInterruptedBuildingReplayWhenAnExistingModuleWouldChange() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        store.failAfterModuleAppends(2);
        var source = ChainContextTestFixtures.source("partial-original");
        var manager = manager(store, source);
        var request = new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-partial-conflict", ChainRole.PLANNER),
                100_000);

        assertThrows(IllegalStateException.class, () -> manager.freeze(request));
        source.marker("partial-new");

        ChainContextException conflict = assertThrows(ChainContextException.class,
                () -> manager.freeze(request));
        assertEquals(ChainContextErrorCode.CONTEXT_MODULE_REPLAY_MISMATCH, conflict.code());
        assertEquals(2, store.findContextModules("revision-partial-conflict").size());
        assertEquals(ChainContextRevisionStatus.BUILDING,
                store.findContextRevision("revision-partial-conflict").orElseThrow().status());
    }

    @Test
    void recoveryRejectsARequestDigestThatDoesNotDescribeTheRebuiltPrompt() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, ChainContextTestFixtures.source("prompt-digest"));
        manager.freeze(new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-prompt-digest", ChainRole.ANSWER),
                100_000));
        store.replaceCompleteRequestDigest("revision-prompt-digest", "0".repeat(64));

        ChainContextException mismatch = assertThrows(ChainContextException.class,
                () -> manager.recover("task-1", "revision-prompt-digest"));

        assertEquals(ChainContextErrorCode.CONTEXT_REVISION_MANIFEST_MISMATCH, mismatch.code());
    }

    @Test
    void recoveryRejectsAValidlyHashedModuleThatNoLongerMatchesTheManifest() {
        var store = new ChainContextTestFixtures.MemoryContextStore();
        var manager = manager(store, ChainContextTestFixtures.source("manifest-original"));
        manager.freeze(new ChainContextFreezeRequest(
                ChainContextTestFixtures.building("revision-manifest", ChainRole.REFLECTOR),
                100_000));
        ContextModuleRecord original = store.findContextModules("revision-manifest").get(0);
        String changedJson = original.projection().json()
                .replace("manifest-original-projection", "manifest-changed-projection");
        store.replaceModule(new ContextModuleRecord(
                original.contextRevisionId(), original.taskId(), original.moduleOrdinal(),
                original.module(), original.presenceKind(), original.sourceVersion(),
                original.readBoundary(), original.projectionVersion(), original.paginationVersion(),
                original.projectionParameters(), ChainContextTestFixtures.json(changedJson),
                original.createdAt()));

        ChainContextException mismatch = assertThrows(ChainContextException.class,
                () -> manager.recover("task-1", "revision-manifest"));

        assertEquals(ChainContextErrorCode.CONTEXT_REVISION_MANIFEST_MISMATCH, mismatch.code());
    }

    private static DefaultChainContextManager manager(
            ChainContextTestFixtures.MemoryContextStore store,
            ChainContextSource source) {
        return new DefaultChainContextManager(
                store,
                store,
                source,
                Clock.fixed(Instant.parse("2026-08-07T02:00:00Z"), ZoneOffset.UTC),
                () -> "completion-token");
    }
}
