package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class E2bCommandFactoryTest {
    @Test
    void passesOnlyStructuredArgumentsToPinnedHelper() {
        E2bCommandFactory factory = new E2bCommandFactory("/opt/e2b/bin/python", "/app/e2b_provider.py",
                "yanban-research-v1");
        assertThat(factory.provider()).isEqualTo("e2b");
        Path workspace = Path.of("/work/1");
        assertThat(factory.create("yb-1", workspace, 2, 536870912L, 900000L))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "create", "--name", "yb-1",
                        "--workspace", workspace.toString(), "--template", "yanban-research-v1", "--cpus", "2",
                        "--memory-bytes", "536870912", "--timeout-millis", "900000");
        assertThat(factory.exec("yb-1", List.of("yanban-runner", "python", "study.py")))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "exec", "--name", "yb-1",
                        "--", "yanban-runner", "python", "study.py");
        assertThat(factory.supportsDependencyNetwork()).isTrue();
        assertThat(factory.createWithDependencyNetwork("yb-1", workspace, 2, 536870912L, 900000L))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "create", "--name", "yb-1",
                        "--workspace", workspace.toString(), "--template", "yanban-research-v1", "--cpus", "2",
                        "--memory-bytes", "536870912", "--timeout-millis", "900000", "--dependency-network");
        assertThat(factory.createWithCoordinateDependencyNetwork(
                "yb-1", workspace, 2, 536870912L, 900000L))
                .endsWith("--dependency-network", "--coordinates-only");
        assertThat(factory.verifyDependencyNetwork("yb-1"))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "policy", "--name", "yb-1",
                        "--expect", "dependency-network");
        assertThat(factory.syncWorkspace("yb-1", workspace))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "sync", "--name", "yb-1",
                        "--workspace", workspace.toString());
        assertThat(factory.denyAllNetwork("yb-1"))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "network", "--name", "yb-1",
                        "--mode", "deny-all");
        assertThat(factory.verifyNetworkPolicy("yb-1"))
                .containsExactly("/opt/e2b/bin/python", "/app/e2b_provider.py", "policy", "--name", "yb-1",
                        "--expect", "deny-all");
    }
}
