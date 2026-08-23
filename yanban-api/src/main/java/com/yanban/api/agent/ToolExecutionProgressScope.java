package com.yanban.api.agent;

import java.util.function.Consumer;
import org.springframework.util.StringUtils;

/** Makes the current streaming process sink available to long-running tool executors. */
final class ToolExecutionProgressScope implements AutoCloseable {

    private static final ThreadLocal<Consumer<String>> CURRENT = new ThreadLocal<>();

    private final Consumer<String> previous;

    private ToolExecutionProgressScope(Consumer<String> consumer) {
        this.previous = CURRENT.get();
        if (consumer == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(consumer);
        }
    }

    static ToolExecutionProgressScope open(Consumer<String> consumer) {
        return new ToolExecutionProgressScope(consumer);
    }

    static void emit(String content) {
        Consumer<String> consumer = CURRENT.get();
        if (consumer != null && StringUtils.hasText(content)) {
            consumer.accept(content);
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
