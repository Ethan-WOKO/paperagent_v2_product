package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record TaskFrame(
        TaskFrameId id,
        String objective,
        List<String> targets,
        List<String> deliverables,
        List<String> constraints,
        Optional<ProjectVersionRef> sourceProjectVersion,
        ExecutionProfile executionProfile,
        Instant createdAt) {

    public TaskFrame {
        Contracts.required(id, "taskFrame.id");
        objective = Contracts.text(objective, "taskFrame.objective");
        targets = requiredTextList(targets, "taskFrame.targets", true);
        deliverables = requiredTextList(deliverables, "taskFrame.deliverables", true);
        constraints = requiredTextList(constraints, "taskFrame.constraints", false);
        sourceProjectVersion = Contracts.required(sourceProjectVersion, "taskFrame.sourceProjectVersion");
        Contracts.required(executionProfile, "taskFrame.executionProfile");
        Contracts.required(createdAt, "taskFrame.createdAt");
    }

    private static List<String> requiredTextList(List<String> values, String path, boolean nonEmpty) {
        List<String> copy = Contracts.list(values, path).stream()
                .map(value -> Contracts.text(value, path + "[]"))
                .toList();
        if (nonEmpty && copy.isEmpty()) {
            Contracts.fail(ViolationCode.REQUIRED_VALUE_MISSING, path, "at least one value is required");
        }
        return copy;
    }
}
