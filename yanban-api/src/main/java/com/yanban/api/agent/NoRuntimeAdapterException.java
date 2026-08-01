package com.yanban.api.agent;

/** Raised only when strategy selection completed but no registered adapter supports it. */
public class NoRuntimeAdapterException extends IllegalStateException {
    public NoRuntimeAdapterException(AgentStrategy strategy) {
        super("No runtime adapter available for strategy " + strategy);
    }
}
