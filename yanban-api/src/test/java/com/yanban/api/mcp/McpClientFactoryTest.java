package com.yanban.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.mcp.McpServerProcessConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpClientFactoryTest {

    @Test
    void injectsUserPatUsingTheOfficialGithubVariableAndLegacyAlias() {
        McpProperties properties = new McpProperties();
        properties.getGithub().setCommand(List.of(
                "cmd", "/c", "npx", "-y", "@modelcontextprotocol/server-github"));
        properties.getGithub().setAllowedCommands(List.of("cmd", "npx", "node"));
        UserSettingsService settingsService = mock(UserSettingsService.class);
        SysUserSettings settings = mock(SysUserSettings.class);
        when(settingsService.decryptGithubPat(settings)).thenReturn("github-test-pat");
        McpClientFactory factory = new McpClientFactory(properties, settingsService);

        McpServerProcessConfig config = factory.toConfig(McpServerKind.GITHUB, settings);

        assertThat(config.environment())
                .containsEntry("GITHUB_PERSONAL_ACCESS_TOKEN", "github-test-pat")
                .containsEntry("GITHUB_TOKEN", "github-test-pat");
    }
}
