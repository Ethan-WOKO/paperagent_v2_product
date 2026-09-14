package com.yanban.api.agent;

import com.yanban.core.tool.ToolDescriptor;
import java.util.List;

/** Paper-owned metadata; shared policy still decides whether an invocation is authorized. */
final class PaperPolishToolContract {
    private PaperPolishToolContract() {}

    static ToolDescriptor descriptor(String name) {
        boolean start = "paper_polish_start".equals(name);
        boolean cancel = "paper_task_cancel".equals(name);
        return new ToolDescriptor(name, "v1", "paper-task",
                List.of(ToolDescriptor.CapabilityProfile.CHAT, ToolDescriptor.CapabilityProfile.PROJECT),
                start ? List.of("paper:polish") : cancel ? List.of("task:cancel") : List.of(),
                start ? List.of(ToolDescriptor.ResourceScope.SESSION, ToolDescriptor.ResourceScope.EXTERNAL)
                        : List.of(ToolDescriptor.ResourceScope.SESSION),
                start ? ToolDescriptor.SideEffectType.CREATE : cancel ? ToolDescriptor.SideEffectType.MODIFY
                        : ToolDescriptor.SideEffectType.NONE,
                ToolDescriptor.ConfirmationPolicy.NEVER,
                start ? ToolDescriptor.AsyncMode.EXTERNAL_TASK : ToolDescriptor.AsyncMode.SYNC,
                start ? ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY : ToolDescriptor.IdempotencyPolicy.NONE,
                start ? ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT : cancel ? ToolDescriptor.RepeatPolicy.ALLOW_LIMITED
                        : ToolDescriptor.RepeatPolicy.POLL_UNTIL_TERMINAL, true);
    }
}
