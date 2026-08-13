package com.yanban.api.agent.v2.chain.api;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainPlannerProgressionBoundaryTest {

    @Test
    void persistentPlannerBranchStopsAfterTheFormalPlanCut()
            throws Exception {
        String source = Files.readString(sourcePath());
        int branch = source.indexOf(
                "if (typed instanceof PlannerPayload.PersistentPlan persistent)");
        int nextBranch = source.indexOf(
                "if (typed instanceof PlannerPayload.UserInstructionDisposition",
                branch);
        String persistentBranch = source.substring(branch, nextBranch);

        assertTrue(persistentBranch.contains(
                "ProductChainPlanTransitionDriver.Result transition"));
        assertTrue(persistentBranch.contains(".commitInitial("));
        assertTrue(persistentBranch.contains("new PersistentExecutionCut("));
        assertFalse(persistentBranch.contains("executePersistentSteps("),
                "Planner advance must not execute the persistent Plan inline");
    }

    @Test
    void plannerDoesNotOwnTheRetiredSynchronousExecutionLoop()
            throws Exception {
        String source = Files.readString(sourcePath());

        assertFalse(Arrays.stream(ProjectChainPlannerProgression.class
                        .getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(
                        "executePersistentSteps")));
        assertFalse(source.contains("int turnBound ="));
        assertFalse(source.contains(
                "executionAt.plusMillis(stepOrdinal * 100L"));
        assertFalse(source.contains(
                "stepAt.plusMillis(turn * 4L"));
        assertEquals(
                Arrays.asList("stepCount", "transition", "executionAt"),
                Arrays.stream(ProjectChainPlannerProgression
                                .PersistentExecutionCut.class
                                .getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList());
    }

    @Test
    void recoveryGetsOneNarrowAcceptedProposalEntryPoint()
            throws Exception {
        var method = ProjectChainPlannerProgression.class.getMethod(
                "commitAcceptedProposal",
                ChainPersistenceRecords.TaskRecord.class,
                ChainPersistenceRecords.InstructionRecord.class,
                String.class, Instant.class);

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(ProjectChainPlannerProgression.OfficialSuccessor.class,
                method.getReturnType());
        assertEquals(
                Arrays.asList("authorityType", "authorityRef", "progression"),
                Arrays.stream(ProjectChainPlannerProgression
                                .OfficialSuccessor.class
                                .getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList());
    }

    @Test
    void acceptedProposalRecoveryDoesNotCallTheModelAgain()
            throws Exception {
        String source = Files.readString(sourcePath());
        int entry = source.indexOf(
                "public OfficialSuccessor commitAcceptedProposal(");
        int formalCommit = source.indexOf(
                "private FormalCommit commitFormal(", entry);
        String recoveryEntry = source.substring(entry, formalCommit);

        assertFalse(recoveryEntry.contains("provider.invoke("));
        assertFalse(recoveryEntry.contains("protocol.invoke("));
        assertTrue(recoveryEntry.contains("verifyProposalLineage("));
        assertTrue(recoveryEntry.contains("acceptedStatePrefix("));
        assertTrue(recoveryEntry.contains("verifyOfficialBinding("));
    }

    @Test
    void planRevisionUsesFormalPlanChangeAndPlanningBlockedRemainsTyped()
            throws Exception {
        String source = Files.readString(sourcePath());
        String transitionDriver = Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/chain/api/"
                        + "ProductChainPlanTransitionDriver.java"));
        String planAdapter = Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/chain/api/"
                        + "ProductChainPlanCommitAdapter.java"));
        String stepAdapter = Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/persistence/"
                        + "ProductChainStepAuthorityAdapter.java"));

        assertFalse(source.contains(
                "CHAIN_PLANNER_PLAN_REVISION_CONSUMER_MISSING"));
        assertTrue(source.contains(
                "planTransitions.commitRevision("));
        assertTrue(source.contains(
                "\"PLAN_BINDING\","));
        assertTrue(source.contains(
                "CHAIN_PLANNER_PLANNING_BLOCKED_CONSUMER_MISSING"));
        assertFalse(planAdapter.contains(
                "CHAIN_PLAN_REVISION_ADAPTER_NOT_IMPLEMENTED"));
        assertTrue(transitionDriver.contains(
                "revisions.commitRevision("));
        assertTrue(transitionDriver.contains(
                "new ChainApplicabilityRuntime("));
        assertTrue(transitionDriver.contains(
                ".supersedeForReplan("));
        assertTrue(transitionDriver.contains(
                ".activateNext("));
        assertFalse(stepAdapter.contains(
                "CHAIN_STEP_REPLAN_TERMINAL_ADAPTER_NOT_IMPLEMENTED"));
        assertTrue(stepAdapter.contains("replaySupersession(command)"));
    }

    @Test
    void revisionIdentityIsValidatedBeforeProposalAdmission()
            throws Exception {
        String source = Files.readString(sourcePath());

        int validation = source.indexOf(
                "planTransitions.validateRevisionDraft(currentPlan, revision)");
        int admission = source.indexOf("admission.admit(", validation);

        assertTrue(validation > 0);
        assertTrue(admission > validation,
                "invalid active-Step identity must be repaired before admission");
    }

    private static Path sourcePath() {
        return Path.of("src/main/java/com/yanban/api/agent/v2/chain/api/"
                + "ProjectChainPlannerProgression.java");
    }
}
