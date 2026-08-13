package io.paperagent.v2.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ChainPersistencePortContractTest {
    private static final String SHA = "0".repeat(64);

    @Test
    void persistenceResponsibilitiesRemainSeparateTypedPorts() {
        assertTrue(ChainFoundationRepository.class.isInterface());
        assertTrue(ChainContextRepository.class.isInterface());
        assertTrue(ChainModelRepository.class.isInterface());
        assertTrue(ChainWorkflowRepository.class.isInterface());
        assertTrue(ChainFinalizationRepository.class.isInterface());
        assertEquals(0, ChainFoundationRepository.class.getInterfaces().length);
        assertEquals(0, ChainContextRepository.class.getInterfaces().length);
        assertEquals(0, ChainModelRepository.class.getInterfaces().length);
        assertEquals(0, ChainWorkflowRepository.class.getInterfaces().length);
        assertEquals(0, ChainFinalizationRepository.class.getInterfaces().length);
        for (Class<?> repository : List.of(
                ChainFoundationRepository.class, ChainContextRepository.class,
                ChainModelRepository.class, ChainWorkflowRepository.class,
                ChainFinalizationRepository.class)) {
            assertFalse(Arrays.stream(repository.getMethods()).anyMatch(method ->
                    method.getName().startsWith("append") || method.getName().startsWith("create")
                            || method.getName().startsWith("commit") || method.getName().startsWith("fail")
                            || method.getName().startsWith("register") || method.getName().startsWith("block")));
        }
    }

    @Test
    void readinessQueriesUseStableIdentityOrUniqueScope() throws Exception {
        assertEquals(java.util.Optional.class, ChainFinalizationRepository.class
                .getMethod("findReadinessById", String.class).getReturnType());
        assertEquals(java.util.Optional.class, ChainFinalizationRepository.class
                .getMethod("findReadinessByScope", String.class).getReturnType());
    }

    @Test
    void recoveryReadPortsExposeEveryChainAuthorityNeededForMechanicalResume()
            throws Exception {
        assertReadMethods(ChainFoundationRepository.class,
                "findCommand", "findTask", "findInstruction",
                "findTaskInstructions", "findAuthorityEvents",
                "highestAuthorityEventSequence");
        assertReadMethods(ChainModelRepository.class,
                "findInvocation", "highestInvocationOrdinal",
                "findInvocations", "highestProviderAttemptNo",
                "findProviderAttempts",
                "findContents", "findContent", "findProposal",
                "findProposalByInvocation",
                "findProposalStateEvents");
        assertReadMethods(ChainContextRepository.class,
                "findContextRevision", "findContextRevisions",
                "findContextModules");
        assertReadMethods(ChainWorkflowRepository.class,
                "findTransition", "findTransitionStages",
                "findIncompleteTransitions", "findRouteDecisions",
                "findInstructionDispositions",
                "findPlanBindings", "findCandidateStepResults",
                "findReviewDecisions", "findAcceptedResults",
                "findApplicabilityDecisions", "findPendingItems",
                "findOpenPendingItems", "findPendingItemEvents",
                "findPermissionDecisions", "findActionBindings",
                "findInFlightActions", "findWorkspaceCandidates",
                "findModelFailureStepBlocks",
                "findActionReceiptStepBlocks");
        assertReadMethods(ChainFinalizationRepository.class,
                "findReadinessById", "findReadinessByScope",
                "findReadiness", "findFinalizationChecks",
                "findTaskOutcome", "findDeliveries",
                "findIncompleteDeliveries", "findDeliveryEvents");
    }

    @Test
    void deletionPortRequiresExistingNumericOwnershipIdentity() throws Exception {
        var method = ChainSessionDeletionPort.class.getMethod(
                "deleteOwnedSessionData", long.class, long.class);
        assertEquals(long.class, method.getReturnType());
    }

    @Test
    void v70RecordsExposeFrozenAuthorityAndReplayColumns() {
        assertHas(ChainPersistenceRecords.CommandRecord.class,
                "commandId", "userId", "sessionId", "clientRequestId", "commandKind",
                "targetTaskId", "targetClientRequestId", "gapId", "requestSha256",
                "turnId", "userMessageId", "resultEventId",
                "status", "resultTaskId", "resultInstructionId", "resultCode", "createdAt", "committedAt");
        assertHas(ChainPersistenceRecords.TaskRecord.class, "taskId", "createdByCommandId", "sourceInstructionId",
                "predecessorTaskId", "userId", "sessionId", "turnId", "requestMessageId",
                "rootClientRequestId", "rootRequestSha256", "projectId", "initialProjectVersion",
                "nextEventSequence", "createdAt");
        assertHas(ChainPersistenceRecords.AuthorityEventRecord.class,
                "eventId", "taskId", "eventSequence", "eventType",
                "transitionId", "sourceIdentitySha256", "committedAt");
    }

    @Test
    void commandCommitRequiresTheExactCausalEventIdentity() throws Exception {
        var method = ChainCommandWriter.class.getMethod(
                "commitCommand", String.class, String.class,
                String.class, String.class);
        assertEquals(ChainPersistenceRecords.CommandRecord.class,
                method.getReturnType());
    }

    @Test
    void v71RecordsExposeCompletionTokenFrozenModulesAndRefOnlyProposal() {
        assertHas(ChainPersistenceRecords.ContextRevisionRecord.class,
                "instructionId", "taskFrameId", "planId", "planRevisionId", "stepId", "activationEventId",
                "projectId", "projectVersion", "workspaceId", "candidateArtifactId", "candidateFingerprint",
                "validationId", "validationRequestDigest", "validationReceiptDigest", "projectorSetVersion",
                "paginationVersion", "runtimePolicyVersion", "moduleCount", "requestManifest",
                "requestDigest", "completionToken", "blockedErrorCode", "inputDigest");
        assertHas(ChainPersistenceRecords.ContextModuleRecord.class,
                "moduleOrdinal", "sourceVersion", "readBoundary", "projectionVersion", "paginationVersion",
                "projectionParameters", "projection");
        assertHas(ChainPersistenceRecords.ModelInvocationRecord.class,
                "completionToken", "provider", "model", "runtimePolicyVersion");
        assertHas(ChainPersistenceRecords.ModelProposalRecord.class,
                "schemaVersion", "payload", "sourceRefs", "bodyAuthorityType", "bodyAuthorityRef");
    }

    @Test
    void v72AndV73RecordsKeepTransitionsFactsAndDeliveryEventsAppendOnly() {
        assertHas(ChainPersistenceRecords.TransitionRecord.class,
                "transitionId", "taskId", "eventId", "transitionType", "sourceDecisionId", "targetIdentityDigest");
        assertFalse(componentNames(ChainPersistenceRecords.TransitionRecord.class).contains("status"));
        assertHas(ChainPersistenceRecords.ResultApplicabilityRecord.class,
                "acceptedResultId", "sourceType", "sourceDecisionId", "targetTaskFrameId",
                "targetPlanId", "targetPlanRevisionId", "targetCandidateKey",
                "targetInstructionVersionId", "conclusion", "eventId");
        assertHas(ChainPersistenceRecords.FinalizationReadinessRecord.class,
                "readinessId", "transitionId", "finalStepId", "artifactId", "candidateKey",
                "validationId", "readinessScopeKey", "instructionId", "projectVersion");
        assertHas(ChainPersistenceRecords.TaskOutcomeRecord.class,
                "sourceCommandId", "instructionId", "taskFrameId", "finalPlanRevisionId",
                "coverage", "acceptedSet", "finalArtifactId", "candidateKey",
                "finalizationReadinessId", "finalizationCheckId",
                "validationId", "validationRequestDigest",
                "validationReceiptDigest", "publishRequirement",
                "publishRequirementDigest", "publishOperationId",
                "publishedProjectVersion", "publishedRevisionId",
                "publishReceiptId", "incompleteItems",
                "limitations", "risks", "failureCategory", "sourceDecisionId");
        assertHas(ChainPersistenceRecords.ActionReceiptStepBlockRecord.class,
                "actionId", "receiptId", "receiptPayloadSha256",
                "repairProposalId", "repairContextRevisionId",
                "repairProposalSignatureSha256",
                "progressAuthorityEventCut",
                "progressSnapshotDigestSha256",
                "thresholdObservedOccurrences", "receiptStatus",
                "failureCode", "blockReasonCode", "runtimePolicyVersion",
                "versionFenceSha256", "blockIdentityDigestSha256");
        assertFalse(componentNames(ChainPersistenceRecords.DeliveryRecord.class).contains("status"));
        assertHas(ChainPersistenceRecords.DeliveryEventRecord.class,
                "deliveryId", "eventKind", "attemptNo", "runtimePolicyVersion", "errorCode", "eventId");
    }

    @Test
    void formalAppendPortsRequireOneAtomicAuthorityEventAndFactCommand() {
        assertAtomicAppend(ChainTransitionWriter.class, "appendTransition");
        assertAtomicAppend(ChainReviewDecisionWriter.class, "appendReviewDecision");
        assertAtomicAppend(ChainTaskOutcomeWriter.class, "appendTaskOutcome");
        assertAtomicAppend(ChainProposalStateWriter.class, "appendProposalState");
        assertAtomicAppend(ChainActionReceiptStepBlockWriter.class,
                "appendActionReceiptStepBlock");
        assertFalse(Arrays.stream(ChainFoundationRepository.class.getMethods())
                .anyMatch(method -> method.getName().equals("appendAuthorityEvent")));

        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                "event", "task-a", "TRANSITION", null, SHA, java.time.Instant.EPOCH);
        var identity = new ChainIdentity.Transition(
                ChainTransitionType.GAP_RESOLUTION, "task-b", "decision", SHA);
        var mismatchedFact = new ChainPersistenceRecords.TransitionRecord(
                identity.transitionId(), "task-b", "event", ChainTransitionType.GAP_RESOLUTION,
                "decision", SHA, java.time.Instant.EPOCH);
        assertThrows(IllegalArgumentException.class,
                () -> new ChainPersistenceRecords.AuthoritativeFact<>(event, mismatchedFact));
    }

    @Test
    void applicabilityPersistenceTupleRejectsNonAsciiSystemIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.ResultApplicabilityRecord(
                "applicability", "task", "event", "accepted", ChainApplicability.SourceType.ACCEPT_STEP,
                "decision", "task-frame", "plan", "revision", "候选", "instruction",
                ChainApplicability.Outcome.APPLICABLE, "reason", java.time.Instant.EPOCH));
    }

    @Test
    void canonicalHashesAndTransitionIdentityAreMechanicallyValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainPersistenceRecords.CanonicalJson(1, "ABC", "{}"));
        ChainIdentity.Transition identity = new ChainIdentity.Transition(
                ChainTransitionType.ACCEPT_STEP, "task", "decision", SHA);
        new ChainPersistenceRecords.TransitionRecord(
                identity.transitionId(), "task", "event", ChainTransitionType.ACCEPT_STEP,
                "decision", SHA, java.time.Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.TransitionRecord(
                "transition.wrong", "task", "event", ChainTransitionType.ACCEPT_STEP,
                "decision", SHA, java.time.Instant.EPOCH));
        ChainPersistenceRecords.TransitionStageRecord stage = new ChainPersistenceRecords.TransitionStageRecord(
                identity.transitionId(), ChainTransitionStage.ACCEPTED_RESULT_COMMITTED, "task", "event",
                1, null, null, null, null, java.time.Instant.EPOCH);
        stage.validateFor(ChainTransitionType.ACCEPT_STEP);
        stage.validateNextFor(ChainTransitionType.ACCEPT_STEP, List.of(ChainTransitionStage.OPEN));
        assertThrows(IllegalArgumentException.class, () -> stage.validateFor(ChainTransitionType.PLAN_CHANGE));
        assertThrows(IllegalArgumentException.class,
                () -> stage.validateNextFor(ChainTransitionType.ACCEPT_STEP, List.of()));
    }

    @Test
    void v73RecordsMatchReadinessCheckOutcomeAndDeliveryShapes() {
        ChainPersistenceRecords.CanonicalJson json = new ChainPersistenceRecords.CanonicalJson(1, SHA, "{}");
        new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness", "task", "event", "transition", SHA, "task-frame", "plan", "revision", 1,
                "step", "review", json, 0, null, ChainIdentity.NONE, "workspace", ChainIdentity.NONE,
                null, null, json, ChainPublishRequirement.NOT_REQUIRED, SHA, "instruction", "version",
                java.time.Instant.EPOCH);

        var storedAttemptBeyondV1 = new ChainPersistenceRecords.FinalizationCheckRecord(
                "check", "task", "event", "readiness", "transition", 3, "task-frame", "revision", SHA,
                ChainIdentity.NONE, "workspace", ChainIdentity.NONE, null, null, SHA, "instruction", "version",
                SHA, SHA, SHA, ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE, ChainRuntimePolicy.V1.policyVersion(),
                java.time.Instant.EPOCH);
        assertEquals(3, storedAttemptBeyondV1.attemptNo());
        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.FinalizationCheckRecord(
                "check", "task", "event", "readiness", "transition", 1, "task-frame", "revision", SHA,
                ChainIdentity.NONE, "workspace", ChainIdentity.NONE, SHA, SHA, SHA, "instruction", "version",
                SHA, SHA, SHA, ChainFinalization.Outcome.PASSED, null,
                ChainFinalization.FailureHandling.NONE, ChainRuntimePolicy.V1.policyVersion(),
                java.time.Instant.EPOCH));

        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome", "task", "event", "command", ChainTaskOutcomeStatus.COMPLETED, "instruction",
                "task-frame", null, null, json, json, null, ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null, json, json, json, null, null, "decision", java.time.Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome", "task", "event", "command", ChainTaskOutcomeStatus.COMPLETED, "instruction",
                null, null, null, json, json, null, ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null, json, json, json, "failure", "code", "decision",
                java.time.Instant.EPOCH));
        completedOutcome(ChainIdentity.NONE, null, null,
                ChainPublishRequirement.NOT_REQUIRED,
                null, null, null, null);
        completedOutcome("validation-bundle", SHA, SHA,
                ChainPublishRequirement.REQUIRED,
                "operation", "project-v2", 2L, "publish-receipt");
        new ChainPersistenceRecords.TaskOutcomeRecord(
                "failed-outcome", "task", "event", "command",
                ChainTaskOutcomeStatus.FAILED, "instruction",
                "task-frame", "plan", "revision", json, json, null,
                ChainIdentity.NONE, "validation-bundle", null, null,
                null, null, json, json, json, "EXECUTION", "FAILED",
                "decision", java.time.Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> completedOutcome(
                "validation-bundle", null, null,
                ChainPublishRequirement.NOT_REQUIRED,
                null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> completedOutcome(
                ChainIdentity.NONE, SHA, SHA,
                ChainPublishRequirement.NOT_REQUIRED,
                null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> completedOutcome(
                ChainIdentity.NONE, null, null,
                ChainPublishRequirement.NOT_REQUIRED,
                "operation", "project-v2", 2L, "publish-receipt"));
        assertThrows(IllegalArgumentException.class, () -> completedOutcome(
                "validation-bundle", SHA, SHA,
                ChainPublishRequirement.REQUIRED,
                null, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.DeliveryRecord(
                "delivery", "task", "event", "command", "route", "outcome", null, null,
                null, null, java.time.Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.DeliveryEventRecord(
                "delivery", 1, "task", "event", ChainDeliveryStatus.PENDING, 1, null,
                ChainRuntimePolicy.V1.policyVersion(), java.time.Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new ChainPersistenceRecords.DeliveryEventRecord(
                "delivery", 1, "task", "event", ChainDeliveryStatus.RETRYING, 1, null,
                ChainRuntimePolicy.V1.policyVersion(), java.time.Instant.EPOCH));
    }

    private static void assertHas(Class<?> type, String... names) {
        Set<String> actual = componentNames(type);
        for (String name : names) {
            assertTrue(actual.contains(name), () -> type.getSimpleName() + " missing " + name);
        }
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord completedOutcome(
            String validationId, String validationRequestDigest,
            String validationReceiptDigest,
            ChainPublishRequirement publishRequirement,
            String publishOperationId, String publishedProjectVersion,
            Long publishedRevisionId, String publishReceiptId) {
        ChainPersistenceRecords.CanonicalJson json =
                new ChainPersistenceRecords.CanonicalJson(1, SHA, "{}");
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome", "task", "event", "command",
                ChainTaskOutcomeStatus.COMPLETED, "instruction",
                "task-frame", "plan", "revision", json, json, null,
                ChainIdentity.NONE, "readiness", "check", validationId,
                validationRequestDigest, validationReceiptDigest,
                publishRequirement, SHA, publishOperationId,
                publishedProjectVersion, publishedRevisionId,
                publishReceiptId, json, json, json, null, null,
                "transition", java.time.Instant.EPOCH);
    }

    private static Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static void assertAtomicAppend(Class<?> port, String methodName) {
        var method = Arrays.stream(port.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        assertEquals(1, method.getParameterCount());
        assertEquals(ChainPersistenceRecords.AuthoritativeFact.class, method.getParameterTypes()[0]);
        assertEquals(ChainPersistenceRecords.AuthoritativeAppendResult.class, method.getReturnType());
    }

    private static void assertReadMethods(Class<?> port, String... methods) {
        Set<String> actual = Arrays.stream(port.getMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(methods), actual);
    }
}
