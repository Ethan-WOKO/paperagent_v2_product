package io.paperagent.v2.providers;

/**
 * Executes exactly one synchronous model turn.
 */
@FunctionalInterface
public interface ModelProvider {
    ModelProviderResult complete(ModelRequest request);
}
