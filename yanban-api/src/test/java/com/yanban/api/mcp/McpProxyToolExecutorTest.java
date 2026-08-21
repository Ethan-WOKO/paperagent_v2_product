package com.yanban.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

class McpProxyToolExecutorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesOnlyExplicitReadOnlyMcpOperationsToTheModel() {
        McpProxyToolExecutor githubRead = executor(
                McpServerKind.GITHUB, "mcp_github__search_code", "search_code");
        McpProxyToolExecutor githubWrite = executor(
                McpServerKind.GITHUB, "mcp_github__create_issue", "create_issue");
        McpProxyToolExecutor filesystemRead = executor(
                McpServerKind.FILESYSTEM, "mcp_fs__read_file", "read_file");
        McpProxyToolExecutor filesystemWrite = executor(
                McpServerKind.FILESYSTEM, "mcp_fs__write_file", "write_file");

        assertThat(githubRead.descriptor().modelVisible()).isTrue();
        assertThat(githubRead.descriptor().sideEffectType())
                .isEqualTo(ToolDescriptor.SideEffectType.EXTERNAL_READ);
        assertThat(filesystemRead.descriptor().modelVisible()).isTrue();
        assertThat(filesystemRead.descriptor().sideEffectType())
                .isEqualTo(ToolDescriptor.SideEffectType.READ_ONLY);
        assertThat(githubWrite.descriptor().modelVisible()).isFalse();
        assertThat(filesystemWrite.descriptor().modelVisible()).isFalse();
    }

    private McpProxyToolExecutor executor(
            McpServerKind kind, String localName, String remoteName) {
        return new McpProxyToolExecutor(
                kind, localName, remoteName, "test", json.createObjectNode(),
                mock(McpClientFactory.class), mock(UserSettingsService.class),
                mock(FilesystemPathGuard.class), json);
    }
}
