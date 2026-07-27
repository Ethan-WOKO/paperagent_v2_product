package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBoundaryTest {
    @Test
    void portsDoNotExposeInMemoryImplementations() {
        List<Class<?>> ports = List.of(
                TaskFrameRepository.class,
                PlanRepository.class,
                EventRepository.class,
                ReceiptRepository.class,
                CheckpointRepository.class,
                PlanBootstrapRepository.class,
                LeaseRepository.class,
                ExecutionStartRepository.class,
                ExecutionStartRecoveryRepository.class,
                PlanExecutionContextRepository.class,
                StepActivationRepository.class,
                StepCompletionRepository.class,
                StepInterruptionRepository.class,
                PlanReplanRepository.class,
                StepRecoveryRepository.class,
                IdempotencyRepository.class);

        for (Class<?> port : ports) {
            assertTrue(port.isInterface());
            for (Method method : port.getMethods()) {
                assertFalse(
                        method.getReturnType().getSimpleName().startsWith("InMemory"),
                        method.toString());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertFalse(
                            parameterType.getSimpleName().startsWith("InMemory"),
                            method.toString());
                }
            }
        }
    }

    @Test
    void implementationClassesRemainPackagePrivate() {
        Set<Class<?>> implementations = Set.of(
                InMemoryTaskFrameRepository.class,
                InMemoryPlanRepository.class,
                InMemoryEventRepository.class,
                InMemoryReceiptRepository.class,
                InMemoryCheckpointRepository.class,
                InMemoryPlanBootstrapRepository.class,
                InMemoryLeaseRepository.class,
                InMemoryExecutionStartRepository.class,
                InMemoryExecutionStartRecoveryRepository.class,
                InMemoryExecutionMutationAuthority.class,
                InMemoryPlanExecutionContextRepository.class,
                InMemoryPlanExecutionContextAuthority.class,
                InMemoryStepActivationRepository.class,
                InMemoryStepCompletionRepository.class,
                InMemoryStepInterruptionRepository.class,
                InMemoryPlanReplanRepository.class,
                InMemoryStepRecoveryRepository.class,
                InMemoryIdempotencyRepository.class,
                InMemoryState.class);
        implementations.forEach(type -> assertFalse(Modifier.isPublic(type.getModifiers())));
    }

    @Test
    void eventRepositoryHasOnlyThePlanGlobalHardCutSurface() {
        Map<String, Method> methods = Arrays.stream(
                        EventRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));

        assertEquals(Set.of("append", "find", "readAfter"), methods.keySet());
        assertMethod(
                methods.get("append"),
                PersistenceResult.class,
                EventEnvelope.class);
        assertMethod(
                methods.get("find"),
                PersistenceResult.class,
                EventId.class);
        assertMethod(
                methods.get("readAfter"),
                PersistenceResult.class,
                PlanId.class,
                long.class);
        assertFalse(methods.containsKey("read"));
    }

    @Test
    void leaseRepositoryHasOnlyTheTrustedTimeHardCutSurface() {
        Map<String, Method> methods = Arrays.stream(
                        LeaseRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));

        assertEquals(Set.of("acquire", "renew", "release", "find"), methods.keySet());
        assertMethod(
                methods.get("acquire"),
                PersistenceResult.class,
                PlanId.class,
                String.class,
                String.class,
                java.time.Instant.class);
        assertMethod(
                methods.get("renew"),
                PersistenceResult.class,
                PlanId.class,
                String.class,
                java.time.Instant.class);
        assertMethod(
                methods.get("release"),
                PersistenceResult.class,
                PlanId.class,
                String.class);
        assertMethod(
                methods.get("find"),
                PersistenceResult.class,
                PlanId.class);
    }

    @Test
    void inMemoryPersistenceHasExactlyDefaultAndClockConstructors() {
        Set<List<Class<?>>> signatures = Arrays.stream(
                        InMemoryPersistence.class.getConstructors())
                .map(Constructor::getParameterTypes)
                .map(List::of)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(
                Set.of(List.of(), List.of(Clock.class)),
                signatures);
    }

    @Test
    void executionStartSurfaceIsExactAndDoesNotExposeLeaseTokenInResult()
            throws Exception {
        Method start = Arrays.stream(ExecutionStartRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()))
                .get("start");
        assertEquals(1, ExecutionStartRepository.class.getDeclaredMethods().length);
        assertMethod(
                start,
                PersistenceResult.class,
                ExecutionStartRequest.class);

        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "startEvent",
                        "startedCheckpoint"),
                Arrays.stream(ExecutionStartRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.Checkpoint.class),
                Arrays.stream(ExecutionStartRequest.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "leaseOwnerId",
                        "fencingToken",
                        "startEvent",
                        "startedCheckpoint"),
                Arrays.stream(PersistedExecutionStart.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        VersionedCheckpoint.class),
                Arrays.stream(PersistedExecutionStart.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                ExecutionStartRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("executionStarts")
                        .getReturnType());
    }

    @Test
    void executionStartRecoverySurfaceIsExactAndTokenFree()
            throws Exception {
        Method inspect = Arrays.stream(
                        ExecutionStartRecoveryRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()))
                .get("inspect");
        assertEquals(
                1,
                ExecutionStartRecoveryRepository.class
                        .getDeclaredMethods()
                        .length);
        assertMethod(inspect, PersistenceResult.class, PlanId.class);

        assertTrue(ExecutionStartRecoverySnapshot.class.isSealed());
        Map<String, Method> snapshotMethods = Arrays.stream(
                        ExecutionStartRecoverySnapshot.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));
        assertEquals(Set.of("planId"), snapshotMethods.keySet());
        assertMethod(snapshotMethods.get("planId"), PlanId.class);
        assertEquals(
                Set.of(
                        PersistedExecutionStartReady.class,
                        PersistedExecutionStartCommitted.class),
                Set.of(ExecutionStartRecoverySnapshot.class
                        .getPermittedSubclasses()));
        assertEquals(
                List.of("bootstrap", "currentPlan"),
                Arrays.stream(
                                PersistedExecutionStartReady.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(PersistedPlanBootstrap.class, io.paperagent.v2.contracts.Plan.class),
                Arrays.stream(
                                PersistedExecutionStartReady.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of("bootstrap", "currentPlan", "executionStart"),
                Arrays.stream(
                                PersistedExecutionStartCommitted.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PersistedPlanBootstrap.class,
                        io.paperagent.v2.contracts.Plan.class,
                        PersistedExecutionStart.class),
                Arrays.stream(
                                PersistedExecutionStartCommitted.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        for (Class<?> snapshot : List.of(
                PersistedExecutionStartReady.class,
                PersistedExecutionStartCommitted.class)) {
            assertTrue(Arrays.stream(snapshot.getRecordComponents())
                    .noneMatch(component ->
                            component.getType() == ExecutionStartRequest.class
                                    || component.getType() == LeaseRecord.class));
            assertTrue(Arrays.stream(snapshot.getDeclaredFields())
                    .noneMatch(field ->
                            field.getType() == ExecutionStartRequest.class
                                    || field.getType() == LeaseRecord.class));
        }
        assertEquals(
                ExecutionStartRecoveryRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("executionStartRecovery")
                        .getReturnType());
    }

    @Test
    void stepActivationSurfaceIsExactAndTokenFreeInResult()
            throws Exception {
        assertTrue(StepActivationRepository.class.isInterface());
        assertEquals(
                1,
                StepActivationRepository.class.getDeclaredMethods().length);
        assertMethod(
                StepActivationRepository.class.getDeclaredMethod(
                        "activate", StepActivationRequest.class),
                PersistenceResult.class,
                StepActivationRequest.class);
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "stepId",
                        "activationEvent",
                        "activatedCheckpoint"),
                Arrays.stream(StepActivationRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanRevisionId.class,
                        long.class,
                        long.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanStepId.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.Checkpoint.class),
                Arrays.stream(StepActivationRequest.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "stepId",
                        "leaseOwnerId",
                        "fencingToken",
                        "activationEvent",
                        "activatedCheckpoint"),
                Arrays.stream(PersistedStepActivation.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        io.paperagent.v2.contracts.PlanStepId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        VersionedCheckpoint.class),
                Arrays.stream(PersistedStepActivation.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertTrue(Arrays.stream(PersistedStepActivation.class.getRecordComponents())
                .noneMatch(component ->
                        component.getName().equals("leaseToken")
                                || component.getType()
                                        == StepActivationRequest.class));
        assertEquals(
                StepActivationRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("stepActivations")
                        .getReturnType());
    }

    @Test
    void stepCompletionSurfaceIsExactAndTokenFreeInResult()
            throws Exception {
        assertTrue(StepCompletionRepository.class.isInterface());
        assertEquals(
                1,
                StepCompletionRepository.class.getDeclaredMethods().length);
        assertMethod(
                StepCompletionRepository.class.getDeclaredMethod(
                        "complete", StepCompletionRequest.class),
                PersistenceResult.class,
                StepCompletionRequest.class);
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "stepId",
                        "completionFact",
                        "completionEvent",
                        "completedRevision",
                        "completedCheckpoint"),
                Arrays.stream(StepCompletionRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanRevisionId.class,
                        long.class,
                        long.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanStepId.class,
                        io.paperagent.v2.contracts.CompletionFact.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.PlanRevision.class,
                        io.paperagent.v2.contracts.Checkpoint.class),
                Arrays.stream(StepCompletionRequest.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "stepId",
                        "leaseOwnerId",
                        "fencingToken",
                        "completionEvent",
                        "completedRevision",
                        "completedCheckpoint"),
                Arrays.stream(PersistedStepCompletion.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        io.paperagent.v2.contracts.PlanStepId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.PlanRevision.class,
                        VersionedCheckpoint.class),
                Arrays.stream(PersistedStepCompletion.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertTrue(Arrays.stream(PersistedStepCompletion.class.getRecordComponents())
                .noneMatch(component ->
                        component.getName().equals("leaseToken")
                                || component.getType()
                                        == StepCompletionRequest.class));
        assertEquals(
                StepCompletionRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("stepCompletions")
                        .getReturnType());
    }

    @Test
    void stepInterruptionSurfaceIsExactAndStateBearingTextIsRedacted()
            throws Exception {
        assertTrue(StepInterruptionRepository.class.isInterface());
        Map<String, Method> methods = Arrays.stream(
                        StepInterruptionRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));
        assertEquals(Set.of("pause", "fail", "cancel"), methods.keySet());
        assertMethod(
                methods.get("pause"),
                PersistenceResult.class,
                StepPauseRequest.class);
        assertMethod(
                methods.get("fail"),
                PersistenceResult.class,
                StepFailRequest.class);
        assertMethod(
                methods.get("cancel"),
                PersistenceResult.class,
                StepCancelRequest.class);
        assertFalse(methods.containsKey("resume"));
        assertFalse(methods.containsKey("transition"));

        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "stepId",
                        "pauseEvent",
                        "pausedCheckpoint"),
                Arrays.stream(StepPauseRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "stepId",
                        "failureEvent",
                        "failedCheckpoint"),
                Arrays.stream(StepFailRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "stepId",
                        "cancellationEvent",
                        "cancelledCheckpoint"),
                Arrays.stream(StepCancelRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "stepId",
                        "kind",
                        "leaseOwnerId",
                        "fencingToken",
                        "interruptionEvent",
                        "interruptedCheckpoint"),
                Arrays.stream(PersistedStepInterruption.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals("<provided>", StepInterruptionKind.PAUSE.toString());
        assertFalse(StepInterruptionKind.PAUSE.toString().contains("PAUSE"));
        assertEquals(
                StepInterruptionRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("stepInterruptions")
                        .getReturnType());
    }

    @Test
    void planReplanSurfaceIsExactAndTokenFreeInResult()
            throws Exception {
        assertTrue(PlanReplanRepository.class.isInterface());
        assertEquals(1, PlanReplanRepository.class.getDeclaredMethods().length);
        assertMethod(
                PlanReplanRepository.class.getDeclaredMethod(
                        "replan", PlanReplanRequest.class),
                PersistenceResult.class,
                PlanReplanRequest.class);
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "replanEvent",
                        "replannedRevision",
                        "replannedCheckpoint"),
                Arrays.stream(PlanReplanRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanRevisionId.class,
                        long.class,
                        long.class,
                        long.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.PlanRevision.class,
                        io.paperagent.v2.contracts.Checkpoint.class),
                Arrays.stream(PlanReplanRequest.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "leaseOwnerId",
                        "fencingToken",
                        "replanEvent",
                        "replannedRevision",
                        "replannedCheckpoint"),
                Arrays.stream(PersistedPlanReplan.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.PlanRevision.class,
                        VersionedCheckpoint.class),
                Arrays.stream(PersistedPlanReplan.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertTrue(Arrays.stream(PersistedPlanReplan.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("leaseToken")
                        || component.getType() == PlanReplanRequest.class));
        assertEquals(
                PlanReplanRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("planReplans")
                        .getReturnType());
    }

    @Test
    void planExecutionContextSurfaceIsExactAndTokenFree()
            throws Exception {
        Map<String, Method> methods = Arrays.stream(
                        PlanExecutionContextRepository.class
                                .getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));
        assertEquals(Set.of("reserve", "confirm", "inspect"),
                methods.keySet());
        assertMethod(
                methods.get("reserve"),
                PersistenceResult.class,
                PlanExecutionContextReservationRequest.class);
        assertMethod(
                methods.get("confirm"),
                PersistenceResult.class,
                PlanExecutionContextConfirmationRequest.class);
        assertMethod(
                methods.get("inspect"),
                PersistenceResult.class,
                PlanId.class);

        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "materializationSpec"),
                Arrays.stream(
                                PlanExecutionContextReservationRequest.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanRevisionId.class,
                        long.class,
                        long.class,
                        long.class,
                        io.paperagent.v2.contracts
                                .WorkspaceMaterializationSpec.class),
                Arrays.stream(
                                PlanExecutionContextReservationRequest.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "materializationSpec",
                        "sourceManifestFingerprint"),
                Arrays.stream(
                                PlanExecutionContextConfirmationRequest.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts
                                .WorkspaceMaterializationSpec.class,
                        io.paperagent.v2.contracts.ContentHash.class),
                Arrays.stream(
                                PlanExecutionContextConfirmationRequest.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertTrue(PlanExecutionContextSnapshot.class.isSealed());
        Map<String, Method> snapshotMethods = Arrays.stream(
                        PlanExecutionContextSnapshot.class
                                .getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));
        assertEquals(
                Set.of("planId", "materializationSpec"),
                snapshotMethods.keySet());
        assertMethod(snapshotMethods.get("planId"), PlanId.class);
        assertMethod(
                snapshotMethods.get("materializationSpec"),
                io.paperagent.v2.contracts
                        .WorkspaceMaterializationSpec.class);
        assertEquals(
                Set.of(
                        PersistedPlanExecutionContextReserved.class,
                        PersistedPlanExecutionContextConfirmed.class),
                Set.of(PlanExecutionContextSnapshot.class
                        .getPermittedSubclasses()));
        assertEquals(
                List.of(
                        "planId",
                        "materializationSpec",
                        "leaseOwnerId",
                        "fencingToken"),
                Arrays.stream(
                                PersistedPlanExecutionContextReserved.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        io.paperagent.v2.contracts
                                .WorkspaceMaterializationSpec.class,
                        String.class,
                        long.class),
                Arrays.stream(
                                PersistedPlanExecutionContextReserved.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "reservation",
                        "leaseOwnerId",
                        "fencingToken",
                        "sourceManifestFingerprint"),
                Arrays.stream(
                                PersistedPlanExecutionContextConfirmed.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PersistedPlanExecutionContextReserved.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts.ContentHash.class),
                Arrays.stream(
                                PersistedPlanExecutionContextConfirmed.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        for (Class<?> result : List.of(
                PersistedPlanExecutionContextReserved.class,
                PersistedPlanExecutionContextConfirmed.class)) {
            assertTrue(Arrays.stream(result.getDeclaredFields())
                    .noneMatch(field ->
                            field.getName().equals("leaseToken")
                                    || field.getType()
                                            == PlanExecutionContextReservationRequest.class
                                    || field.getType()
                                            == PlanExecutionContextConfirmationRequest.class));
        }
        assertEquals(
                PlanExecutionContextRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("planExecutionContexts")
                        .getReturnType());
    }

    @Test
    void stepRecoverySurfaceIsExactAndTokenFree()
            throws Exception {
        assertTrue(StepRecoveryRepository.class.isInterface());
        assertEquals(1, StepRecoveryRepository.class.getDeclaredMethods().length);
        assertMethod(
                StepRecoveryRepository.class.getDeclaredMethod(
                        "inspect", PlanId.class),
                PersistenceResult.class,
                PlanId.class);
        assertTrue(StepRecoverySnapshot.class.isSealed());
        assertEquals(
                Set.of(PersistedStepRecoveryActive.class),
                Set.of(StepRecoverySnapshot.class.getPermittedSubclasses()));
        Map<String, Method> snapshotMethods = Arrays.stream(
                        StepRecoverySnapshot.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));
        assertEquals(Set.of("planId"), snapshotMethods.keySet());
        assertMethod(snapshotMethods.get("planId"), PlanId.class);
        assertEquals(
                List.of(
                        "taskFrame",
                        "plan",
                        "checkpoint",
                        "activation",
                        "executionContext"),
                Arrays.stream(PersistedStepRecoveryActive.class
                                .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        io.paperagent.v2.contracts.TaskFrame.class,
                        io.paperagent.v2.contracts.Plan.class,
                        VersionedCheckpoint.class,
                        PersistedStepActivation.class,
                        java.util.Optional.class),
                Arrays.stream(PersistedStepRecoveryActive.class
                                .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                StepRecoveryRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("stepRecovery")
                        .getReturnType());
        assertTrue(Arrays.stream(PersistedStepRecoveryActive.class
                        .getDeclaredFields())
                .noneMatch(field -> field.getName().equals("leaseToken")
                        || field.getType() == LeaseRecord.class
                        || field.getType() == java.time.Clock.class));
    }

    private static void assertMethod(
            Method method,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
    }
}
