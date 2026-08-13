package com.yanban.api.agent.v2.chain.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectChainSkillSnapshotCoordinatorTest {
    @Test
    void freezesEveryNewTaskBeforePublishingItsBeginCutAndNeverResolvesReplay() throws Exception {
        String source = source();
        int begin = source.indexOf("private StartBegin beginStart(");
        int replay = source.indexOf("if (existing.isPresent())", begin);
        int freeze = source.indexOf("skillSnapshots.freezeNewTask(");
        int completedBegin = source.indexOf(
                "return new StartBegin(session, target, registered.value(), message,",
                freeze);

        assertTrue(replay >= 0 && freeze > replay && completedBegin > freeze,
                "command replay must return before Skill resolution and a new Task must freeze before its begin cut is exposed");
    }

    @Test
    void guardsContinuationAndCopiesBoundaryReplacementBeforePlanner() throws Exception {
        String source = source();
        int preservation = source.indexOf("skillSnapshots.preservesSelection(");
        int messageWrite = source.indexOf("messages.saveAndFlush(", preservation);
        int boundary = source.indexOf(
                "CommandCut replacement = createDispositionReplacement(");
        int copy = source.indexOf(
                "skillSnapshots.copyForBoundaryReplacement(", boundary);
        int replacementPlanner = source.indexOf(
                "progression = plannerProgression.advance(", copy);

        assertTrue(preservation >= 0 && messageWrite > preservation,
                "supplement/correction Skill mismatch must fail before user-message side effects");
        assertTrue(boundary >= 0 && copy > boundary
                        && replacementPlanner > copy,
                "boundary Replacement must copy the old immutable snapshot before its first Planner");
    }

    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/chain/api/"
                        + "ProjectChainTurnCoordinator.java"));
    }
}
