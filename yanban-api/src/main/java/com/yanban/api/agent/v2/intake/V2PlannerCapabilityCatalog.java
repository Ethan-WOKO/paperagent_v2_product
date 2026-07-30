package com.yanban.api.agent.v2.intake;

import io.paperagent.v2.contracts.ToolId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class V2PlannerCapabilityCatalog {
    private static final Map<String, ToolId> ALIASES = aliases();

    private V2PlannerCapabilityCatalog() {
    }

    static List<PublicCapability> publicCapabilities() {
        return List.of(
                new PublicCapability("literature_search",
                        "Search scholarly literature using the product retrieval service."),
                new PublicCapability("project_read",
                        "Read one text file from the authenticated frozen Project version."),
                new PublicCapability("project_search",
                        "Search text inside the authenticated frozen Project version."),
                new PublicCapability("project_candidate",
                        "Create reviewed candidate file changes in an isolated Workspace."),
                new PublicCapability("sandbox_execute",
                        "Execute bounded code or commands in the isolated Sandbox."));
    }

    static ToolId internalToolId(String alias) {
        ToolId id = ALIASES.get(alias);
        if (id == null) {
            throw new V2TurnPlanningException("planner capability is unsupported");
        }
        return id;
    }

    private static Map<String, ToolId> aliases() {
        Map<String, ToolId> values = new LinkedHashMap<>();
        values.put("literature_search", new ToolId("literature.search"));
        values.put("project_read", new ToolId("project.read"));
        values.put("project_search", new ToolId("project.search"));
        values.put("project_candidate", new ToolId("project.candidate.compose"));
        values.put("sandbox_execute", new ToolId("sandbox.execute"));
        return Map.copyOf(values);
    }

    record PublicCapability(String name, String description) {
    }
}
