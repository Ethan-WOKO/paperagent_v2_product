package com.yanban.api.agent.v2.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.agent.v2.adapter.provider.ProductStepTurnException;
import com.yanban.api.settings.UserSettingsService;
import io.paperagent.v2.contracts.PlanId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PlanOwnerModelEndpointResolverTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(
            new DriverManagerDataSource(
                    "jdbc:h2:mem:plan-owner-endpoint;DB_CLOSE_DELAY=-1",
                    "sa", ""));
    private final UserSettingsService settings =
            mock(UserSettingsService.class);

    @BeforeEach
    void resetTables() {
        jdbc.execute("drop table if exists agent_v2_literature_deliveries");
        jdbc.execute("drop table if exists agent_v2_project_analysis_deliveries");
        jdbc.execute("drop table if exists agent_v2_project_candidate_deliveries");
        jdbc.execute("""
                create table agent_v2_literature_deliveries (
                    user_id bigint not null,
                    plan_id varchar(128)
                )
                """);
        jdbc.execute("""
                create table agent_v2_project_analysis_deliveries (
                    user_id bigint not null,
                    plan_id varchar(128)
                )
                """);
        jdbc.execute("""
                create table agent_v2_project_candidate_deliveries (
                    user_id bigint not null,
                    plan_id varchar(128)
                )
                """);
    }

    @Test
    void resolvesExactlyOneDurableOwnersSettingsPageDefaults() {
        jdbc.update("""
                insert into agent_v2_project_analysis_deliveries
                    (user_id, plan_id) values (?, ?)
                """, 7L, "plan-one");
        when(settings.resolveModelEndpoint(7L, null, null))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "custom-provider",
                        "custom-model",
                        "https://owner.example/v1",
                        "owner-api-key",
                        "custom",
                        "Owner endpoint"));

        var endpoint = resolver().resolve(new PlanId("plan-one"));

        assertEquals("custom-provider", endpoint.provider());
        assertEquals("custom-model", endpoint.model());
        assertEquals("owner-api-key", endpoint.apiKey());
        assertEquals("https://owner.example/v1", endpoint.apiUrl());
        assertFalse(endpoint.toString().contains("owner-api-key"));
        assertFalse(endpoint.toString().contains("owner.example"));
        verify(settings).resolveModelEndpoint(7L, null, null);
    }

    @Test
    void missingOwnerFailsClosedBeforeSettingsLookup() {
        assertThrows(ProductStepTurnException.class, () ->
                resolver().resolve(new PlanId("missing")));

        verifyNoInteractions(settings);
    }

    @Test
    void crossDeliveryOrMultipleOwnerBindingFailsClosed() {
        jdbc.update("""
                insert into agent_v2_literature_deliveries
                    (user_id, plan_id) values (?, ?)
                """, 7L, "ambiguous");
        jdbc.update("""
                insert into agent_v2_project_candidate_deliveries
                    (user_id, plan_id) values (?, ?)
                """, 8L, "ambiguous");

        assertThrows(ProductStepTurnException.class, () ->
                resolver().resolve(new PlanId("ambiguous")));

        verifyNoInteractions(settings);
    }

    private PlanOwnerModelEndpointResolver resolver() {
        return new PlanOwnerModelEndpointResolver(jdbc, settings);
    }
}
