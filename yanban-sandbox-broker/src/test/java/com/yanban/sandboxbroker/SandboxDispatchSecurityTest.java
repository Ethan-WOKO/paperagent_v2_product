package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxDispatch;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SandboxDispatchSecurityTest {
    @Test void digestBindsIdentityFilesCommandPolicyResourcesAndFence() {
        SandboxDispatch original = request("", Map.of("pom.xml", "x"), List.of("mvn", "-o", "test"), 7);
        String digest = SandboxCanonicalDigest.compute(original);
        SandboxDispatch changedFile = request("", Map.of("pom.xml", "y"), original.argv(), 7);
        SandboxDispatch changedFence = request("", original.files(), original.argv(), 8);
        assertThat(SandboxCanonicalDigest.compute(changedFile)).isNotEqualTo(digest);
        assertThat(SandboxCanonicalDigest.compute(changedFence)).isNotEqualTo(digest);
    }

    @Test void sameKeyDifferentDigestConflictsAndUnsafePathsFailClosed() {
        SandboxExecutionRepository repository = mock(SandboxExecutionRepository.class);
        SandboxExecutionEntity stored = new SandboxExecutionEntity("e", "key", "a".repeat(64), 7, 1L, "yb-e", "{}", java.time.LocalDateTime.now());
        SandboxDispatchStore store=mock(SandboxDispatchStore.class);when(store.current("key")).thenReturn(stored);
        SandboxDispatchService service = new SandboxDispatchService(repository, new ObjectMapper(), new SandboxProcessRegistry(), store);
        SandboxDispatch valid = signed(request("", Map.of("pom.xml", "x"), List.of("mvn", "-o", "test"), 7));
        assertThatThrownBy(() -> service.dispatch(valid)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        SandboxDispatch traversal = signed(request("", Map.of("../secret", "x"), valid.argv(), 7));
        assertThatThrownBy(() -> service.dispatch(traversal)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsNetworkAndResourceExpansion() {
        SandboxExecutionRepository repository = mock(SandboxExecutionRepository.class);
        SandboxDispatchService service = new SandboxDispatchService(repository, new ObjectMapper(), new SandboxProcessRegistry(), mock(SandboxDispatchStore.class));
        SandboxDispatch base = request("", Map.of("pom.xml", "x"), List.of("mvn", "-o", "test"), 7);
        SandboxDispatch expanded = new SandboxDispatch("key", "", base.userId(), base.projectId(), base.sessionId(),
                base.planId(), base.stepId(), base.fence(), base.projectVersion(), base.policyDigest(), base.files(), base.argv(),
                3, base.memoryBytes(), base.timeoutMillis(), base.maxOutputBytes(), true);
        assertThatThrownBy(() -> service.dispatch(signed(expanded))).isInstanceOf(ResponseStatusException.class);
    }

    private SandboxDispatch request(String digest, Map<String,String> files, List<String> argv, long fence) {
        return new SandboxDispatch("key", digest, 1, 2, 3, 4, 5, fence, "a".repeat(64), "b".repeat(64),
                files, argv, 2, 4_294_967_296L, 900_000, 20_971_520, false);
    }
    private SandboxDispatch signed(SandboxDispatch value) {
        String digest = SandboxCanonicalDigest.compute(value);
        return new SandboxDispatch(value.idempotencyKey(), digest, value.userId(), value.projectId(), value.sessionId(),
                value.planId(), value.stepId(), value.fence(), value.projectVersion(), value.policyDigest(), value.files(),
                value.argv(), value.cpus(), value.memoryBytes(), value.timeoutMillis(), value.maxOutputBytes(), value.networkEnabled());
    }
}
