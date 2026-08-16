package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileReadRequest;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnProjectVersionSourceFactory;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentEngineWorkspaceGatewayTest {
    private static final String TASK = "task." + "1".repeat(64);
    private static final String VERSION = "3".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void listsAndReadsExactFrozenUtf8BytesWithHashAttestation() {
        byte[] content = "class Sort {}\n".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        AgentEngineWorkspaceGateway gateway = gateway(content, hash);
        EngineTaskAuthority authority = authority();

        var list = gateway.list(authority);
        assertThat(list.projectVersion()).isEqualTo(VERSION);
        assertThat(list.files()).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("Sort.java");
            assertThat(file.sha256()).isEqualTo(hash);
            assertThat(file.mediaType()).isEqualTo("text/x-java-source");
        });
        var read = gateway.read(authority, new FileReadRequest("1.0", "Sort.java", hash));
        assertThat(read.content()).isEqualTo("class Sort {}\n");
        assertThat(read.truncated()).isFalse();

        assertThatThrownBy(() -> gateway.read(authority,
                new FileReadRequest("1.0", "Sort.java", "9".repeat(64))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_FILE_HASH_CONFLICT"));
    }

    @Test
    void rejectsTraversalBeforeReadingWorkspace() {
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        AgentEngineWorkspaceGateway gateway = gateway(content, sha256(content));

        assertThatThrownBy(() -> gateway.read(authority(),
                new FileReadRequest("1.0", "../secret", "9".repeat(64))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_PATH_INVALID"));
    }

    private AgentEngineWorkspaceGateway gateway(byte[] content, String hash) {
        AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "12", 11L, 13L, 14L),
                Optional.of(VERSION));
        when(contexts.resolve(11L, 12L)).thenReturn(context);
        when(sources.create(11L, 12L)).thenReturn(version -> {
            assertThat(version).isEqualTo(new ProjectVersionRef("14", VERSION));
            return new ProjectVersionSnapshot(version, List.of(new ProjectFileSnapshot(
                    new ProjectPath("Sort.java"), content,
                    new ContentHash("sha256", hash), Map.of())), Map.of());
        });
        EngineGatewayProperties properties = new EngineGatewayProperties();
        properties.setWorkspaceRoot(temporary.toString());
        return new AgentEngineWorkspaceGateway(
                contexts, sources, new ProjectStorageProperties(), properties);
    }

    private static EngineTaskAuthority authority() {
        return new EngineTaskAuthority(TASK, "2".repeat(64),
                11, 12, 13, 14, VERSION, true, true,
                Instant.parse("2026-08-16T11:00:00Z"));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
