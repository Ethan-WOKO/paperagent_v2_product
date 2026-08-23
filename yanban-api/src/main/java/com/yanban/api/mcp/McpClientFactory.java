package com.yanban.api.mcp;

import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.mcp.DefaultMcpStdioClient;
import com.yanban.mcp.McpServerProcessConfig;
import com.yanban.mcp.McpStdioClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpClientFactory {

    private static final String DISCOVERY_ONLY_GITHUB_TOKEN =
            "paperagent-mcp-discovery-no-api-authority";

    private final McpProperties properties;
    private final UserSettingsService userSettingsService;

    public McpClientFactory(McpProperties properties, UserSettingsService userSettingsService) {
        this.properties = properties;
        this.userSettingsService = userSettingsService;
    }

    public McpStdioClient createForDiscovery(McpServerKind kind) {
        return new DefaultMcpStdioClient(discoveryConfig(kind));
    }

    McpServerProcessConfig discoveryConfig(McpServerKind kind) {
        McpServerProcessConfig config = toConfig(kind, null);
        if (kind != McpServerKind.GITHUB) {
            return config;
        }
        Map<String, String> environment = new HashMap<>(config.environment());
        environment.putIfAbsent("GITHUB_PERSONAL_ACCESS_TOKEN",
                DISCOVERY_ONLY_GITHUB_TOKEN);
        environment.putIfAbsent("GITHUB_TOKEN", DISCOVERY_ONLY_GITHUB_TOKEN);
        return new McpServerProcessConfig(
                config.command(), config.allowedCommands(), environment,
                config.startupTimeout(), config.requestTimeout());
    }

    public McpStdioClient createForUser(McpServerKind kind, Long userId) {
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        return new DefaultMcpStdioClient(toConfig(kind, settings));
    }

    McpServerProcessConfig toConfig(McpServerKind kind, SysUserSettings settings) {
        McpProperties.ServerProperties source = kind == McpServerKind.GITHUB ? properties.getGithub() : properties.getFilesystem();
        List<String> command = new ArrayList<>(source.getCommand());
        Map<String, String> environment = new HashMap<>(source.getEnvironment());
        if (kind == McpServerKind.GITHUB && settings != null) {
            String pat = userSettingsService.decryptGithubPat(settings);
            if (pat != null && !pat.isBlank()) {
                environment.put("GITHUB_PERSONAL_ACCESS_TOKEN", pat);
                environment.put("GITHUB_TOKEN", pat);
            }
        }
        if (kind == McpServerKind.FILESYSTEM && settings != null) {
            command.addAll(userSettingsService.parseFilesystemRoots(settings));
        } else if (kind == McpServerKind.FILESYSTEM) {
            command.addAll(UserSettingsService.DEFAULT_FILESYSTEM_ROOTS);
        }
        return new McpServerProcessConfig(command, source.getAllowedCommands(), environment, source.getStartupTimeout(), source.getRequestTimeout());
    }
}
