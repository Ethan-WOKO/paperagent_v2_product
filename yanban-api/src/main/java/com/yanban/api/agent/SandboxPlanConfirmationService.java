package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStatus;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Durable, user-authenticated confirmation authority for every sandbox Plan execution entry. */
@Service
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
public class SandboxPlanConfirmationService {
    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentSessionRepository sessions;
    private final AgentPlanRunLeaseService leases;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public SandboxPlanConfirmationService(AgentPlanRepository plans, AgentPlanStepRepository steps,
                                          AgentSessionRepository sessions, AgentPlanRunLeaseService leases,
                                          ProjectService projects, ObjectMapper json, JdbcTemplate jdbc) {
        this.plans = plans;
        this.steps = steps;
        this.sessions = sessions;
        this.leases = leases;
        this.projects = projects;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Transactional
    public Confirmation confirmAndQueue(long userId, long planId, String confirmationKey) {
        if (confirmationKey == null || !confirmationKey.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sandbox confirmation idempotency key is invalid");
        }
        AgentPlan plan = plans.findLockedByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        if (plan.terminal() || AgentPlanStatus.PAUSED.name().equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan cannot be confirmed in its current state");
        }
        Scope scope = currentScope(plan, userId);
        Stored existing = lock(planId);
        LocalDateTime now = databaseNow();
        if (existing == null) {
            try {
                jdbc.update("INSERT INTO sandbox_execution_confirmations "
                                + "(plan_id,user_id,session_id,project_id,project_version,sandbox_step_set_digest,confirmation_key,confirmed_at,cancelled_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,NULL)",
                        planId, userId, scope.sessionId(), scope.projectId(), scope.projectVersion(),
                        scope.stepSetDigest(), confirmationKey, now);
            } catch (DataIntegrityViolationException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "sandbox confirmation idempotency key is already bound", exception);
            }
        } else if (!existing.matches(scope, userId)) {
            if (!AgentPlanStatus.REVIEWING.name().equals(plan.getStatus())) {
                throw confirmationRequired();
            }
            try {
                jdbc.update("UPDATE sandbox_execution_confirmations SET user_id=?,session_id=?,project_id=?,"
                                + "project_version=?,sandbox_step_set_digest=?,confirmation_key=?,confirmed_at=?,cancelled_at=NULL "
                                + "WHERE plan_id=?",
                        userId, scope.sessionId(), scope.projectId(), scope.projectVersion(), scope.stepSetDigest(),
                        confirmationKey, now, planId);
            } catch (DataIntegrityViolationException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "sandbox confirmation idempotency key is already bound", exception);
            }
        } else if (!existing.confirmationKey().equals(confirmationKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "sandbox confirmation idempotency key conflicts with the existing confirmation");
        } else if (existing.cancelledAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cancelled sandbox confirmation cannot be reused");
        }

        boolean queued = false;
        if (AgentPlanStatus.REVIEWING.name().equals(plan.getStatus())) {
            queued = leases.queue(planId, userId);
            if (!queued) throw new ResponseStatusException(HttpStatus.CONFLICT, "sandbox Plan could not be queued");
        } else if (!AgentPlanStatus.RUNNING.name().equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sandbox Plan is not confirmable");
        }
        return new Confirmation(scope, queued);
    }

    @Transactional(readOnly = true)
    public void requireCurrent(AgentPlan plan, long userId) {
        if (plan == null) throw confirmationRequired();
        List<AgentPlanStep> sandboxSteps = sandboxSteps(plan.getId());
        if (sandboxSteps.isEmpty()) return;
        Scope current = currentScope(plan, userId, sandboxSteps);
        Stored stored = find(plan.getId());
        if (stored == null || stored.cancelledAt() != null || !stored.matches(current, userId)) {
            throw confirmationRequired();
        }
    }

    @Transactional
    public void cancel(long userId, long planId) {
        jdbc.update("UPDATE sandbox_execution_confirmations SET cancelled_at=? "
                        + "WHERE plan_id=? AND user_id=? AND cancelled_at IS NULL",
                databaseNow(), planId, userId);
    }

    private Scope currentScope(AgentPlan plan, long userId) {
        return currentScope(plan, userId, sandboxSteps(plan.getId()));
    }

    private Scope currentScope(AgentPlan plan, long userId, List<AgentPlanStep> sandboxSteps) {
        if (sandboxSteps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan has no sandbox execution to confirm");
        }
        ProjectRuntimeContext context = ProjectPlanEnvelope.restore(json, plan.getRawPlanJson(), userId);
        if (context == null) throw confirmationRequired();
        AgentSession session = sessions.findByIdAndUserIdAndScopeAndProjectId(
                        plan.getSessionId(), userId, AgentSessionScope.PROJECT, context.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "sandbox confirmation Project binding is invalid"));
        ProjectManifestResponse manifest = projects.manifest(userId, context.projectId());
        return new Scope(session.getId(), context.projectId(), manifest.version(), stepSetDigest(sandboxSteps));
    }

    private List<AgentPlanStep> sandboxSteps(long planId) {
        return steps.findByPlanIdOrderBySortOrderAsc(planId).stream()
                .filter(step -> "SANDBOX_EXECUTE".equals(step.getType()))
                .sorted(Comparator.comparing(AgentPlanStep::getId))
                .toList();
    }

    private String stepSetDigest(List<AgentPlanStep> values) {
        List<String> canonical = new ArrayList<>();
        for (AgentPlanStep step : values) {
            canonical.add(step.getId() + "|" + step.getStepKey() + "|" + step.getSortOrder() + "|"
                    + step.getTitle() + "|" + step.getDescription() + "|" + step.getType() + "|"
                    + step.getDependenciesJson() + "|" + canonicalTools(step.getAllowedToolsJson()) + "|"
                    + step.getSuccessCriteria());
        }
        return sha256(String.join("\n", canonical));
    }

    private String canonicalTools(String value) {
        try {
            var node = json.readTree(value);
            if (node == null || !node.isArray()) throw new IllegalArgumentException("invalid sandbox step tools");
            List<String> tools = new ArrayList<>();
            node.forEach(item -> { if (item.isTextual()) tools.add(item.textValue()); });
            tools.sort(String::compareTo);
            return String.join(",", tools);
        } catch (RuntimeException exception) { throw exception;
        } catch (Exception exception) { throw new IllegalArgumentException("invalid sandbox step tools", exception); }
    }

    private Stored lock(long planId) {
        List<Stored> values = jdbc.query("SELECT user_id,session_id,project_id,project_version,"
                        + "sandbox_step_set_digest,confirmation_key,confirmed_at,cancelled_at "
                        + "FROM sandbox_execution_confirmations WHERE plan_id=? FOR UPDATE",
                (rs, row) -> stored(rs), planId);
        return values.isEmpty() ? null : values.get(0);
    }

    private Stored find(long planId) {
        List<Stored> values = jdbc.query("SELECT user_id,session_id,project_id,project_version,"
                        + "sandbox_step_set_digest,confirmation_key,confirmed_at,cancelled_at "
                        + "FROM sandbox_execution_confirmations WHERE plan_id=?",
                (rs, row) -> stored(rs), planId);
        return values.isEmpty() ? null : values.get(0);
    }

    private Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        return new Stored(rs.getLong("user_id"), rs.getLong("session_id"), rs.getLong("project_id"),
                rs.getString("project_version"), rs.getString("sandbox_step_set_digest"),
                rs.getString("confirmation_key"), rs.getTimestamp("confirmed_at").toLocalDateTime(),
                cancelled == null ? null : cancelled.toLocalDateTime());
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT current_timestamp", LocalDateTime.class);
    }

    private static ResponseStatusException confirmationRequired() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "SANDBOX_CONFIRMATION_REQUIRED: current user, ProjectVersion and sandbox step set must be confirmed");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public record Scope(long sessionId, long projectId, String projectVersion, String stepSetDigest) { }
    public record Confirmation(Scope scope, boolean queued) { }
    private record Stored(long userId, long sessionId, long projectId, String projectVersion,
                          String stepSetDigest, String confirmationKey, LocalDateTime confirmedAt,
                          LocalDateTime cancelledAt) {
        boolean matches(Scope scope, long expectedUserId) {
            return userId == expectedUserId && sessionId == scope.sessionId() && projectId == scope.projectId()
                    && projectVersion.equals(scope.projectVersion()) && stepSetDigest.equals(scope.stepSetDigest());
        }
    }
}
