package com.yanban.sandboxbroker;

import java.nio.file.Path;
import java.util.List;

/** Structured provider operations. Implementations return argv arrays and never host shell strings. */
interface SandboxProviderCommands {
    String provider();
    List<String> health();
    List<String> create(String name, Path workspace, int cpus, long memoryBytes, long timeoutMillis);
    default List<String> createWithDependencyNetwork(String name, Path workspace, int cpus,
                                                     long memoryBytes, long timeoutMillis) {
        return create(name, workspace, cpus, memoryBytes, timeoutMillis);
    }
    default boolean supportsDependencyNetwork() { return false; }
    default List<String> createWithCoordinateDependencyNetwork(String name, Path workspace, int cpus,
            long memoryBytes, long timeoutMillis) {
        return createWithDependencyNetwork(name, workspace, cpus, memoryBytes, timeoutMillis);
    }
    default List<String> verifyDependencyNetwork(String name) {
        throw new UnsupportedOperationException("dependency network is unavailable");
    }
    default List<String> syncWorkspace(String name, Path workspace) {
        throw new UnsupportedOperationException("workspace sync is unavailable");
    }
    List<String> denyAllNetwork(String name);
    List<String> verifyNetworkPolicy(String name);
    List<String> exec(String name, List<String> argv);
    List<String> stop(String name);
    List<String> remove(String name);
    List<String> list();
}
