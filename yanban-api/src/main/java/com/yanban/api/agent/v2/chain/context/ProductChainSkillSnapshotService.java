package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Resolves a Skill once at a Task boundary and only replays persisted facts. */
@Service
public class ProductChainSkillSnapshotService {
    private final ProductChainSkillSnapshotRepository snapshots;
    private final SkillsService skills;

    public ProductChainSkillSnapshotService(
            ProductChainSkillSnapshotRepository snapshots,
            SkillsService skills) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    public ProductChainTaskSkillSnapshot freezeNewTask(
            long userId, String taskId, String sourceInstructionId,
            String requestedSkillId, Instant createdAt) {
        var replay = snapshots.findByTaskId(taskId);
        if (replay.isPresent()) {
            return replay.get();
        }
        String normalizedSkillId = normalized(requestedSkillId);
        ProductChainTaskSkillSnapshot requested;
        if (normalizedSkillId == null) {
            requested = ProductChainTaskSkillSnapshot.none(
                    taskId, sourceInstructionId, createdAt);
        } else {
            ResolvedSkill resolved = skills.resolveEnabledSkill(
                    userId, normalizedSkillId);
            requested = ProductChainTaskSkillSnapshot.selected(
                    taskId, sourceInstructionId, resolved.id(),
                    resolved.prompt(), resolved.allowedTools(), createdAt);
        }
        return snapshots.append(requested);
    }

    public ProductChainTaskSkillSnapshot copyForBoundaryReplacement(
            String replacementTaskId,
            String replacementSourceInstructionId,
            String predecessorTaskId,
            Instant createdAt) {
        var replay = snapshots.findByTaskId(replacementTaskId);
        if (replay.isPresent()) {
            return replay.get();
        }
        ProductChainTaskSkillSnapshot predecessor = require(predecessorTaskId);
        return snapshots.append(predecessor.copyTo(
                replacementTaskId, replacementSourceInstructionId,
                createdAt));
    }

    public boolean preservesSelection(
            String taskId, String requestedSkillId) {
        ProductChainTaskSkillSnapshot snapshot = require(taskId);
        String normalizedSkillId = normalized(requestedSkillId);
        if (normalizedSkillId == null) {
            return true;
        }
        return snapshot.selectionKind()
                == ProductChainTaskSkillSnapshot.SelectionKind.SELECTED
                && normalizedSkillId.equals(snapshot.skillId());
    }

    public ProductChainTaskSkillSnapshot require(String taskId) {
        return snapshots.findByTaskId(taskId).orElseThrow(() ->
                new IllegalStateException("task Skill snapshot is missing"));
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
