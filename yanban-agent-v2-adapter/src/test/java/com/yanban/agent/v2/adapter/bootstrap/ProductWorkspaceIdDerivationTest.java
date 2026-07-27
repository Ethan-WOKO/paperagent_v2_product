package com.yanban.agent.v2.adapter.bootstrap;

import com.yanban.core.agent.AgentRunIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductWorkspaceIdDerivationTest {
    private final ProductWorkspaceIdDerivation workspaceIds =
            new ProductWorkspaceIdDerivation();

    @Test
    void derivesRetryStableWorkspaceIdFromTheProductRunId() {
        AgentRunIdentity identity =
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, 83L);

        assertEquals(
                "product-workspace."
                        + "fc93b055c4f4b806d84febca9bfbbb02fda6fa2c"
                        + "c23d7dc4563efbf837f04d01",
                workspaceIds.derive(identity).value());
        assertEquals(
                workspaceIds.derive(identity),
                workspaceIds.derive(identity));
    }

    @Test
    void workspaceDomainIsDistinctFromPlanDomain() {
        AgentRunIdentity identity =
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, 83L);

        String workspaceId = workspaceIds.derive(identity).value();
        assertNotEquals(
                new ProductPlanIdDerivation().derive(identity).value(),
                workspaceId);
        assertTrue(workspaceId.startsWith("product-workspace."));
    }

    @Test
    void differentTurnsDeriveDifferentWorkspaceIds() {
        assertNotEquals(
                workspaceIds.derive(new AgentRunIdentity(
                        "AGENT_TURN", "42", 7L, 11L, 83L)),
                workspaceIds.derive(new AgentRunIdentity(
                        "AGENT_TURN", "43", 7L, 11L, 83L)));
    }
}
