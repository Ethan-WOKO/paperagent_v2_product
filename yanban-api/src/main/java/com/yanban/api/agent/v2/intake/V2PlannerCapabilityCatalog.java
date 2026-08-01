package com.yanban.api.agent.v2.intake;

import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import io.paperagent.v2.contracts.ToolId;
import java.util.List;

final class V2PlannerCapabilityCatalog {
    private V2PlannerCapabilityCatalog() {
    }

    static List<PublicCapability> publicCapabilities() {
        return V2ProductToolCatalog.entries().stream()
                .map(entry -> new PublicCapability(
                        entry.publicAlias(), entry.publicDescription()))
                .toList();
    }

    static ToolId internalToolId(String alias) {
        return V2ProductToolCatalog.toolIdForPublicAlias(alias)
                .orElseThrow(() -> new V2TurnPlanningException(
                        "planner capability is unsupported"));
    }

    record PublicCapability(String name, String description) {
    }
}
