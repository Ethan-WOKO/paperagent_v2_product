package com.yanban.api.agent.v2.execution;

import com.yanban.agent.v2.adapter.provider.ProductModelEndpoint;
import com.yanban.agent.v2.adapter.provider.ProductModelEndpointResolver;
import com.yanban.agent.v2.adapter.provider.ProductStepTurnError;
import com.yanban.agent.v2.adapter.provider.ProductStepTurnException;
import com.yanban.api.settings.UserSettingsService;
import io.paperagent.v2.contracts.PlanId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Resolves model settings from the single durable product delivery that owns
 * a V2 Plan.
 */
public final class PlanOwnerModelEndpointResolver
        implements ProductModelEndpointResolver {
    private static final String OWNER_QUERY = """
            select user_id
              from agent_v2_literature_deliveries
             where plan_id = ?
            union all
            select user_id
              from agent_v2_project_analysis_deliveries
             where plan_id = ?
            union all
            select user_id
              from agent_v2_project_candidate_deliveries
             where plan_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final UserSettingsService settings;

    public PlanOwnerModelEndpointResolver(
            JdbcTemplate jdbc, UserSettingsService settings) {
        if (jdbc == null || settings == null) {
            throw invalid("modelEndpointResolver");
        }
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public ProductModelEndpoint resolve(PlanId planId) {
        if (planId == null) {
            throw invalid("modelRequest.planId");
        }
        List<Long> owners = jdbc.query(
                OWNER_QUERY,
                (result, row) -> result.getLong("user_id"),
                planId.value(), planId.value(), planId.value());
        if (owners.size() != 1 || owners.get(0) == null
                || owners.get(0) <= 0) {
            throw invalid("modelRequest.planId.owner");
        }
        UserSettingsService.ModelEndpoint endpoint =
                settings.resolveModelEndpoint(owners.get(0), null, null);
        if (endpoint == null) {
            throw invalid("modelRequest.planId.endpoint");
        }
        return new ProductModelEndpoint(
                endpoint.providerKey(),
                endpoint.modelName(),
                endpoint.apiKey(),
                endpoint.apiUrl());
    }

    private static ProductStepTurnException invalid(String path) {
        return new ProductStepTurnException(
                ProductStepTurnError.INVALID_AUTHORITY, path);
    }
}
