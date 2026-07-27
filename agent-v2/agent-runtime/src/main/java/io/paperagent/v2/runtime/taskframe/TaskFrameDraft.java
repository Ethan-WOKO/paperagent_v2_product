package io.paperagent.v2.runtime.taskframe;

import java.util.List;

/**
 * A structured, non-authoritative TaskFrame draft.
 *
 * <p>Only structural validity is enforced here. Semantic validity remains the
 * responsibility of the canonical TaskFrame contract at freeze time.
 */
public record TaskFrameDraft(
        String objective,
        List<String> targets,
        List<String> deliverables,
        List<String> constraints) {

    public TaskFrameDraft {
        TaskFrameFreezeValues.required(objective, "taskFrameDraft.objective");
        targets = TaskFrameFreezeValues.list(targets, "taskFrameDraft.targets");
        deliverables = TaskFrameFreezeValues.list(
                deliverables,
                "taskFrameDraft.deliverables");
        constraints = TaskFrameFreezeValues.list(
                constraints,
                "taskFrameDraft.constraints");
    }
}
