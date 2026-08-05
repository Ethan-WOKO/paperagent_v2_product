package com.yanban.sandboxbroker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds calls to the pinned E2B SDK helper without accepting a shell command string. */
final class E2bCommandFactory implements SandboxProviderCommands {
    private final String python;
    private final String helper;
    private final String template;

    E2bCommandFactory(String python, String helper, String template) {
        if (python == null || python.isBlank() || helper == null || helper.isBlank()
                || template == null || template.isBlank()) throw new IllegalArgumentException();
        this.python = python;
        this.helper = helper;
        this.template = template;
    }

    @Override public String provider() { return "e2b"; }
    @Override public List<String> health() { return base("health"); }
    @Override public boolean supportsDependencyNetwork() { return true; }
    @Override public List<String> create(String name, Path workspace, int cpus, long memoryBytes, long timeoutMillis) {
        return base("create", "--name", name, "--workspace", workspace.toString(), "--template", template,
                "--cpus", Integer.toString(cpus), "--memory-bytes", Long.toString(memoryBytes),
                "--timeout-millis", Long.toString(timeoutMillis));
    }
    @Override public List<String> createWithDependencyNetwork(String name, Path workspace, int cpus,
                                                              long memoryBytes, long timeoutMillis) {
        ArrayList<String> command = new ArrayList<>(create(name, workspace, cpus, memoryBytes, timeoutMillis));
        command.add("--dependency-network");
        return List.copyOf(command);
    }
    @Override public List<String> createWithDeclaredDependencyNetwork(String name, Path workspace,
            int cpus, long memoryBytes, long timeoutMillis) {
        ArrayList<String> command = new ArrayList<>(
                createWithDependencyNetwork(name, workspace, cpus, memoryBytes, timeoutMillis));
        command.add("--dependency-declarations-only");
        return List.copyOf(command);
    }
    @Override public List<String> verifyDependencyNetwork(String name) {
        return base("policy", "--name", name, "--expect", "dependency-network");
    }
    @Override public List<String> syncWorkspace(String name, Path workspace) {
        return base("sync", "--name", name, "--workspace", workspace.toString());
    }
    @Override public List<String> denyAllNetwork(String name) {
        return base("network", "--name", name, "--mode", "deny-all");
    }
    @Override public List<String> verifyNetworkPolicy(String name) {
        return base("policy", "--name", name, "--expect", "deny-all");
    }
    @Override public List<String> exec(String name, List<String> argv) {
        ArrayList<String> command = new ArrayList<>(base("exec", "--name", name));
        command.add("--");
        command.addAll(argv);
        return List.copyOf(command);
    }
    @Override public List<String> stop(String name) { return base("kill", "--name", name); }
    @Override public List<String> remove(String name) { return base("kill", "--name", name); }
    @Override public List<String> list() { return base("list"); }

    private List<String> base(String... args) {
        ArrayList<String> command = new ArrayList<>();
        command.add(python);
        command.add(helper);
        command.addAll(List.of(args));
        return List.copyOf(command);
    }
}
