package com.yanban.core.tool;

import java.util.List;
import java.util.Map;

/**
 * Transitional metadata catalog for already-registered domain executors. Owners can replace an
 * entry by overriding {@link ToolExecutor#descriptor()}; names absent from this catalog remain
 * {@code UNKNOWN} and cannot be model-exposed.
 */
final class ToolDescriptorCatalog {

    private static final List<ToolDescriptor.CapabilityProfile> CHAT_AND_PROJECT = List.of(
            ToolDescriptor.CapabilityProfile.CHAT, ToolDescriptor.CapabilityProfile.PROJECT);

    private static final Map<String, ToolDescriptor> DESCRIPTORS = Map.ofEntries(
            Map.entry("search_web", descriptor("search_web", "external-research",
                    ToolDescriptor.SideEffectType.EXTERNAL_READ, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.ConfirmationPolicy.NEVER,
                    true, "research:web")),
            Map.entry("recommend_literature", descriptor("recommend_literature", "literature-research",
                    ToolDescriptor.SideEffectType.CREATE, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.EXTERNAL, ToolDescriptor.ResourceScope.SESSION),
                    ToolDescriptor.ConfirmationPolicy.NEVER, true, "research:literature")),
            Map.entry("search_knowledge", descriptor("search_knowledge", "knowledge-retrieval",
                    ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.USER_KNOWLEDGE), ToolDescriptor.ConfirmationPolicy.NEVER, true)),
            Map.entry("literature_search_start", descriptor("literature_search_start", "literature-task",
                    ToolDescriptor.SideEffectType.CREATE, ToolDescriptor.AsyncMode.EXTERNAL_TASK,
                    ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.EXTERNAL, ToolDescriptor.ResourceScope.SESSION,
                            ToolDescriptor.ResourceScope.PROJECT),
                    ToolDescriptor.ConfirmationPolicy.NEVER, true, "research:literature")),
            Map.entry("literature_search_status", descriptor("literature_search_status", "literature-task",
                    ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.POLL_UNTIL_TERMINAL,
                    List.of(ToolDescriptor.ResourceScope.SESSION), ToolDescriptor.ConfirmationPolicy.NEVER, true)),
            Map.entry("literature_search_result", descriptor("literature_search_result", "literature-task",
                    ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.POLL_UNTIL_TERMINAL,
                    List.of(ToolDescriptor.ResourceScope.SESSION), ToolDescriptor.ConfirmationPolicy.NEVER, true)),
            Map.entry("literature_search_cancel", descriptor("literature_search_cancel", "literature-task",
                    ToolDescriptor.SideEffectType.MODIFY, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.SESSION), ToolDescriptor.ConfirmationPolicy.NEVER,
                    true, "task:cancel")),
            Map.entry("paper_polish_status", descriptor("paper_polish_status", "task-observation",
                    ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.SESSION), ToolDescriptor.ConfirmationPolicy.NEVER, true)),
            Map.entry("paper_polish_result", descriptor("paper_polish_result", "task-observation",
                    ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.SESSION), ToolDescriptor.ConfirmationPolicy.NEVER, true)),
            Map.entry("paper_task_cancel", descriptor("paper_task_cancel", "task-control",
                    ToolDescriptor.SideEffectType.MODIFY, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    List.of(ToolDescriptor.ResourceScope.SESSION), ToolDescriptor.ConfirmationPolicy.ON_SIDE_EFFECT,
                    true, "task:cancel"))
    );

    private ToolDescriptorCatalog() {
    }

    static ToolDescriptor descriptorFor(String name) {
        return DESCRIPTORS.getOrDefault(name, ToolDescriptor.legacyUnknown(name));
    }

    private static ToolDescriptor descriptor(String name,
                                             String domain,
                                             ToolDescriptor.SideEffectType sideEffect,
                                             ToolDescriptor.AsyncMode asyncMode,
                                             ToolDescriptor.IdempotencyPolicy idempotency,
                                             ToolDescriptor.RepeatPolicy repeat,
                                             List<ToolDescriptor.ResourceScope> scopes,
                                             ToolDescriptor.ConfirmationPolicy confirmationPolicy,
                                             boolean modelVisible,
                                             String... requiredPermissions) {
        return new ToolDescriptor(name, "v1", domain, CHAT_AND_PROJECT, List.of(requiredPermissions), scopes,
                sideEffect, confirmationPolicy,
                asyncMode, idempotency, repeat, modelVisible);
    }
}
