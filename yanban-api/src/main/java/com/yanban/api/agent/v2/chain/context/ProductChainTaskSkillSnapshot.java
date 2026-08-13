package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeSet;

/** One immutable, source-instruction-bound Skill selection for a Task. */
public record ProductChainTaskSkillSnapshot(
        String taskId,
        String sourceInstructionId,
        SelectionKind selectionKind,
        String skillId,
        String promptSha256,
        String promptBody,
        CanonicalJson allowedTools,
        String snapshotSha256,
        Instant createdAt) {

    private static final String NONE = "NONE";

    public ProductChainTaskSkillSnapshot {
        required(taskId, "taskId");
        required(sourceInstructionId, "sourceInstructionId");
        Objects.requireNonNull(selectionKind, "selectionKind");
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!sha256(allowedTools.json()).equals(allowedTools.sha256())) {
            throw new IllegalArgumentException("allowedTools digest mismatch");
        }
        if (selectionKind == SelectionKind.NONE) {
            if (skillId != null || promptSha256 != null || promptBody != null) {
                throw new IllegalArgumentException(
                        "NONE snapshot cannot carry Skill prompt identity");
            }
            if (!"[]".equals(allowedTools.json())) {
                throw new IllegalArgumentException(
                        "NONE snapshot must have an empty allowed-tools set");
            }
        } else {
            required(skillId, "skillId");
            required(promptBody, "promptBody");
            if (!sha256(promptBody).equals(promptSha256)) {
                throw new IllegalArgumentException("prompt digest mismatch");
            }
        }
        if (!snapshotDigest(taskId, sourceInstructionId, selectionKind,
                skillId, promptSha256, allowedTools.sha256())
                .equals(snapshotSha256)) {
            throw new IllegalArgumentException("snapshot digest mismatch");
        }
    }

    public static ProductChainTaskSkillSnapshot none(
            String taskId, String sourceInstructionId, Instant createdAt) {
        CanonicalJson tools = canonicalTools(java.util.List.of());
        return new ProductChainTaskSkillSnapshot(
                taskId, sourceInstructionId, SelectionKind.NONE,
                null, null, null, tools,
                snapshotDigest(taskId, sourceInstructionId,
                        SelectionKind.NONE, null, null, tools.sha256()),
                createdAt);
    }

    public static ProductChainTaskSkillSnapshot selected(
            String taskId, String sourceInstructionId, String skillId,
            String promptBody, Collection<String> allowedTools,
            Instant createdAt) {
        required(skillId, "skillId");
        required(promptBody, "promptBody");
        CanonicalJson tools = canonicalTools(allowedTools);
        String promptSha256 = sha256(promptBody);
        return new ProductChainTaskSkillSnapshot(
                taskId, sourceInstructionId, SelectionKind.SELECTED,
                skillId.trim(), promptSha256, promptBody, tools,
                snapshotDigest(taskId, sourceInstructionId,
                        SelectionKind.SELECTED, skillId.trim(),
                        promptSha256, tools.sha256()), createdAt);
    }

    public ProductChainTaskSkillSnapshot copyTo(
            String replacementTaskId, String replacementSourceInstructionId,
            Instant replacementCreatedAt) {
        return new ProductChainTaskSkillSnapshot(
                replacementTaskId, replacementSourceInstructionId,
                selectionKind, skillId, promptSha256, promptBody, allowedTools,
                snapshotDigest(replacementTaskId,
                        replacementSourceInstructionId, selectionKind,
                        skillId, promptSha256, allowedTools.sha256()),
                replacementCreatedAt);
    }

    private static CanonicalJson canonicalTools(Collection<String> tools) {
        Objects.requireNonNull(tools, "allowedTools");
        TreeSet<String> sorted = new TreeSet<>();
        for (String tool : tools) {
            required(tool, "allowedTool");
            sorted.add(tool.trim());
        }
        StringBuilder json = new StringBuilder("[");
        int index = 0;
        for (String tool : sorted) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append('"').append(escape(tool)).append('"');
        }
        String body = json.append(']').toString();
        return new CanonicalJson(1, sha256(body), body);
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append("\\u")
                                .append(String.format("%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.toString();
    }

    private static String snapshotDigest(
            String taskId, String sourceInstructionId,
            SelectionKind selectionKind, String skillId,
            String promptSha256, String allowedToolsSha256) {
        return sha256("task-skill-snapshot-v1\0" + taskId + "\0"
                + sourceInstructionId + "\0" + selectionKind.name() + "\0"
                + Objects.toString(skillId, NONE) + "\0"
                + Objects.toString(promptSha256, NONE) + "\0"
                + allowedToolsSha256);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum SelectionKind {
        NONE,
        SELECTED
    }
}
