package com.yanban.api.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.api.ProjectChainTurnApi;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnListItem;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnResponse;
import com.yanban.api.agent.v2.chain.api.V2TurnCancelRequest;
import com.yanban.api.agent.v2.chain.api.V2TurnCommandRequest;
import com.yanban.api.agent.v2.chain.api.V2TurnCommandResponse;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
final class ExternalProductEngineTurnService implements ProjectChainTurnApi {
    private final ProductEngineProperties properties;
    private final ProductEngineControlClient engines;
    private final ProductEngineTurnRepository repository;
    private final ProductEngineTurnTransactions transactions;
    private final ProductEngineContextAssembler contextAssembler;
    private final ObjectProvider<AgentEngineTaskGrantService> grantServices;
    private final AgentSessionRepository sessions;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final ProductEngineCanonicalJson canonical;

    ExternalProductEngineTurnService(ProductEngineProperties properties,
                                     ProductEngineControlClient engines,
                                     ProductEngineTurnRepository repository,
                                     ProductEngineTurnTransactions transactions,
                                     ProductEngineContextAssembler contextAssembler,
                                     ObjectProvider<AgentEngineTaskGrantService> grantServices,
                                     AgentSessionRepository sessions,
                                     ProjectService projects,
                                     ObjectMapper json) {
        this.properties = properties; this.engines = engines; this.repository = repository;
        this.transactions = transactions; this.contextAssembler = contextAssembler;
        this.grantServices = grantServices; this.sessions = sessions; this.projects = projects;
        this.json = json; this.canonical = new ProductEngineCanonicalJson(json);
    }

    @Override
    public V2NaturalLanguageTurnResponse start(long userId, long sessionId,
                                                V2NaturalLanguageTurnRequest request) {
        try {
            Objects.requireNonNull(request, "request");
            AgentSession session = ownedProjectSession(userId, sessionId);
            ProductEngineMode mode = properties.selectedMode();
            if (!mode.external()) throw new IllegalStateException("external Engine service selected in legacy mode");
            String instructionKind = instructionKind(request.instructionKind(), request.targetClientRequestId());
            String productRequestDigest = canonical.digest(new ProductEngineDtos.ProductRequestFingerprint(
                    request.content(), request.ragDisabled(), request.skillId(), instructionKind,
                    normalized(request.targetClientRequestId())));

            ProductEngineTurnEntity known = transactions.find(userId, sessionId, request.clientRequestId()).orElse(null);
            ProductEngineTurnTransactions.Begin begin;
            ProductEngineTurnEntity entity;
            if (known != null) {
                mode = known.mode();
                if (!known.question().equals(request.content())
                        || !known.productRequestDigest().equals(productRequestDigest)) {
                    throw new ProductEngineControlException(409, "ENGINE_PRODUCT_REQUEST_CONFLICT");
                }
                begin = new ProductEngineTurnTransactions.Begin(known.id(), known.agentTurnId(), true);
                entity = known;
            } else {
                ProjectManifestResponse manifest = projects.manifest(userId, session.getProjectId());
                requireManifest(session, manifest);
                String instruction = contextAssembler.assemble(session, userId, manifest.version(),
                        authoritativeInstruction(request, instructionKind));
                ProductEngineDtos.Authority authority = authority(session, manifest.version(), instruction);
                String digest = canonical.digest(authority);
                String taskId = "task." + ProductEngineCanonicalJson.sha256(
                        userId + "\0" + sessionId + "\0" + request.clientRequestId());
                String authorityJson = canonical.canonical(authority);
                begin = transactions.begin(mode, userId, sessionId, session.getProjectId(), manifest.version(),
                        request.clientRequestId(), taskId, digest, productRequestDigest,
                        authorityJson, request.content());
                entity = transactions.require(userId, sessionId, request.clientRequestId());
            }
            entity = synchronize(entity);
            return new V2NaturalLanguageTurnResponse(sessionId, entity.agentTurnId(), entity.userMessageId(),
                    entity.assistantMessageId(), entity.rootClientRequestId(), "PERSISTENT_PLAN_EXECUTE",
                    entity.finalText(), entity.engineTaskId(), begin.replayed(), entity.rootClientRequestId());
        } catch (ProductEngineControlException failure) {
            throw publicFailure(failure);
        }
    }

    @Override
    public V2TurnCommandResponse reply(long userId, long sessionId, String targetClientRequestId,
                                       String gapId, V2TurnCommandRequest request) {
        try {
            ownedProjectSession(userId, sessionId);
            ProductEngineTurnEntity entity = synchronize(transactions.require(userId, sessionId, targetClientRequestId));
            String digest = ProductEngineCanonicalJson.sha256(request.content());
            if (request.clientRequestId().equals(entity.lastAnswerClientRequestId())) {
                if (!digest.equals(entity.lastAnswerDigest()) || !gapId.equals(entity.lastAnswerQuestionId())) {
                    throw new ProductEngineControlException(409, "ENGINE_PRODUCT_ANSWER_CONFLICT");
                }
                return commandResponse(entity, request.clientRequestId(), engineAnswerId(request.clientRequestId()), true);
            }
            if (gapId.equals(entity.lastAnswerQuestionId()) && !digest.equals(entity.lastAnswerDigest())) {
                throw new ProductEngineControlException(409, "ENGINE_PRODUCT_ANSWER_CONFLICT");
            }
            if (entity.pendingQuestionId() == null || !entity.pendingQuestionId().equals(gapId)) {
                if (!gapId.equals(entity.lastAnswerQuestionId()) || !digest.equals(entity.lastAnswerDigest())) {
                    throw new ProductEngineControlException(409, "ENGINE_QUESTION_NOT_PENDING");
                }
            }
            String engineRequestId = engineAnswerId(request.clientRequestId());
            engines.answer(entity.mode(), entity.engineTaskId(), new ProductEngineDtos.Answer(
                    "1.0", engineRequestId, gapId, request.content(), digest));
            transactions.recordAnswer(userId, sessionId, targetClientRequestId,
                    request.clientRequestId(), gapId, digest);
            entity = synchronize(transactions.require(userId, sessionId, targetClientRequestId));
            return commandResponse(entity, request.clientRequestId(), engineRequestId, false);
        } catch (ProductEngineControlException failure) {
            throw publicFailure(failure);
        }
    }

    @Override
    public V2TurnCommandResponse cancel(long userId, long sessionId, String targetClientRequestId,
                                        V2TurnCancelRequest request) {
        try {
            ownedProjectSession(userId, sessionId);
            ProductEngineTurnEntity entity = transactions.require(userId, sessionId, targetClientRequestId);
            String engineRequestId = engineCancelId(request.clientRequestId());
            boolean replayed = request.clientRequestId().equals(entity.lastCancelClientRequestId());
            if (!replayed) {
                engines.cancel(entity.mode(), entity.engineTaskId(),
                        new ProductEngineDtos.Cancel("1.0", engineRequestId));
                transactions.recordCancel(userId, sessionId, targetClientRequestId, request.clientRequestId());
            }
            entity = synchronize(transactions.require(userId, sessionId, targetClientRequestId));
            return commandResponse(entity, request.clientRequestId(), engineRequestId, replayed);
        } catch (ProductEngineControlException failure) {
            throw publicFailure(failure);
        }
    }

    @Override
    public V2ProjectTurnResponse get(long userId, long sessionId, String rootClientRequestId) {
        try {
            ownedProjectSession(userId, sessionId);
            return projection(synchronize(transactions.require(userId, sessionId, rootClientRequestId)));
        } catch (ProductEngineControlException failure) {
            throw publicFailure(failure);
        }
    }

    @Override
    public List<V2ProjectTurnListItem> list(long userId, long sessionId, int limit) {
        ownedProjectSession(userId, sessionId);
        int bounded = Math.max(1, Math.min(limit, 100));
        return repository.findByUserIdAndSessionIdOrderByCreatedAtDescIdDesc(
                userId, sessionId, PageRequest.of(0, bounded)).stream().map(entity -> {
            V2ProjectTurnResponse value = projection(entity);
            return new V2ProjectTurnListItem(value.clientRequestId(), entity.question(), entity.createdAt(),
                    entity.updatedAt(), value.workState(), value.taskOutcomeStatus(), value.deliveryStatus(),
                    value.route(), value.planId(), value.baseProjectVersion(), value.publishedProjectVersion(),
                    value.revisionId(), value.publishReceiptId(), value.steps(), value.pendingItem(),
                    value.validation(), value.finalText(), value.candidateArtifactId(), value.outputPaths(),
                    value.failureCategory(), value.failureCode(), value.deliveryErrorCode());
        }).toList();
    }

    boolean owns(long userId, long sessionId, String rootClientRequestId) {
        return transactions.find(userId, sessionId, rootClientRequestId).isPresent();
    }

    V2ProjectTurnResponse persistedGet(long userId, long sessionId, String rootClientRequestId) {
        ownedProjectSession(userId, sessionId);
        return projection(transactions.require(userId, sessionId, rootClientRequestId));
    }

    V2NaturalLanguageTurnResponse persistedStart(long userId, long sessionId,
                                                  V2NaturalLanguageTurnRequest request) {
        ownedProjectSession(userId, sessionId);
        ProductEngineTurnEntity entity = transactions.require(userId, sessionId, request.clientRequestId());
        String kind = instructionKind(request.instructionKind(), request.targetClientRequestId());
        String digest = canonical.digest(new ProductEngineDtos.ProductRequestFingerprint(
                request.content(), request.ragDisabled(), request.skillId(), kind,
                normalized(request.targetClientRequestId())));
        if (!entity.question().equals(request.content()) || !entity.productRequestDigest().equals(digest)) {
            throw publicFailure(new ProductEngineControlException(409, "ENGINE_PRODUCT_REQUEST_CONFLICT"));
        }
        return new V2NaturalLanguageTurnResponse(sessionId, entity.agentTurnId(), entity.userMessageId(),
                entity.assistantMessageId(), entity.rootClientRequestId(), "PERSISTENT_PLAN_EXECUTE",
                entity.finalText(), entity.engineTaskId(), true, entity.rootClientRequestId());
    }

    private ProductEngineTurnEntity synchronize(ProductEngineTurnEntity entity) {
        if (terminal(entity.engineState())) return entity;
        submitWithFreshGrant(entity);
        ProductEngineDtos.TaskView view = engines.get(entity.mode(), entity.engineTaskId());
        validateView(entity, view);
        List<ProductEngineDtos.Event> events = engines.events(entity.mode(), entity.engineTaskId(),
                entity.lastSequence(), view.lastSequence());
        ProductEngineTurnEntity applied = transactions.apply(
                entity.userId(), entity.sessionId(), entity.rootClientRequestId(), events);
        if (applied.lastSequence() != view.lastSequence()
                || !applied.engineState().equals(view.state())) {
            throw new ProductEngineControlException(502, "ENGINE_TASK_PROJECTION_MISMATCH");
        }
        return applied;
    }

    private void submitWithFreshGrant(ProductEngineTurnEntity entity) {
        ProjectManifestResponse current = projects.manifest(entity.userId(), entity.projectId());
        if (current == null || !entity.projectVersion().equals(current.version())) {
            throw new ProductEngineControlException(409, "ENGINE_PROJECT_VERSION_CHANGED");
        }
        AgentEngineTaskGrantService grants = grantServices.getIfAvailable();
        if (grants == null) throw new ProductEngineControlException(503, "ENGINE_GATEWAY_DISABLED");
        EngineTaskGrant grant = grants.issue(entity.engineTaskId(), entity.requestDigest(),
                entity.userId(), entity.agentTurnId());
        try {
            ProductEngineDtos.Authority authority = json.readValue(
                    entity.authorityJson(), ProductEngineDtos.Authority.class);
            ProductEngineDtos.Accepted accepted = engines.submit(entity.mode(), new ProductEngineDtos.Submission(
                    "1.0", entity.engineTaskId(), entity.requestDigest(), authority,
                    new ProductEngineDtos.Gateway(grant.value(), grant.expiresAt())));
            validateView(entity, accepted.task());
        } catch (ProductEngineControlException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("persisted Engine authority is invalid", failure);
        }
    }

    private ProductEngineDtos.Authority authority(AgentSession session, String version, String instruction) {
        return new ProductEngineDtos.Authority("PERSISTENT_PLAN_EXECUTE", "session." + session.getId(),
                new ProductEngineDtos.Project(String.valueOf(session.getProjectId()), version),
                instruction, new ProductEngineDtos.Permissions(true, false, true),
                new ProductEngineDtos.Model(session.getModelProviderSnapshot(), session.getModelSnapshot()));
    }

    private V2ProjectTurnResponse projection(ProductEngineTurnEntity entity) {
        String state = entity.engineState();
        boolean terminal = terminal(state);
        String workState = "waiting_user".equals(state) ? "WAITING_USER" : terminal ? "TERMINAL" : "EXECUTING";
        String outcome = "succeeded".equals(state) ? "COMPLETED"
                : "failed".equals(state) ? "FAILED" : "cancelled".equals(state) ? "CANCELLED" : null;
        String delivery = "succeeded".equals(state) ? "SUCCEEDED" : null;
        String stepStatus = "waiting_user".equals(state) ? "WAITING_GAP" : terminal ? "COMPLETED" : "ACTIVE";
        String detail = entity.failureCode() != null ? entity.failureCode()
                : terminal ? "Engine execution finished." : "Engine execution is in progress.";
        V2ProjectTurnResponse.PendingItem pending = entity.pendingQuestionId() == null ? null
                : new V2ProjectTurnResponse.PendingItem(entity.pendingQuestionId(), "USER_INFORMATION",
                "PENDING", entity.pendingQuestionText(), "Plain text answer");
        return new V2ProjectTurnResponse(entity.rootClientRequestId(), workState, outcome, delivery,
                "PERSISTENT_PLAN_EXECUTE", entity.engineTaskId(), entity.projectVersion(), null,
                null, null, List.of(new V2ProjectTurnResponse.Step("engine-execution", 1,
                "Agent Engine execution", stepStatus, detail)), pending, null, entity.finalText(),
                null, List.of(), entity.failureCategory(), entity.failureCode(), null);
    }

    private V2TurnCommandResponse commandResponse(ProductEngineTurnEntity entity, String productRequestId,
                                                  String instructionId, boolean replayed) {
        String pending = entity.pendingQuestionId() == null ? "RESPONSE_RECEIVED" : "PENDING";
        return new V2TurnCommandResponse(entity.rootClientRequestId(), productRequestId, instructionId,
                pending, projection(entity).taskOutcomeStatus(), replayed);
    }

    private void validateView(ProductEngineTurnEntity entity, ProductEngineDtos.TaskView view) {
        if (view == null || !"1.0".equals(view.contractVersion())
                || !entity.engineTaskId().equals(view.taskId())
                || !entity.requestDigest().equals(view.requestDigest()) || view.lastSequence() < 0) {
            throw new ProductEngineControlException(502, "ENGINE_TASK_BINDING_MISMATCH");
        }
    }

    private AgentSession ownedProjectSession(long userId, long sessionId) {
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ProductEngineControlException(404, "ENGINE_PRODUCT_SESSION_NOT_FOUND"));
        if (session.getScope() != AgentSessionScope.PROJECT || session.getProjectId() == null) {
            throw new ProductEngineControlException(400, "ENGINE_PRODUCT_PROJECT_SESSION_REQUIRED");
        }
        return session;
    }

    private void requireManifest(AgentSession session, ProjectManifestResponse manifest) {
        if (manifest == null || !session.getProjectId().equals(manifest.projectId())
                || manifest.version() == null || !manifest.version().matches("^[a-f0-9]{64}$")) {
            throw new ProductEngineControlException(409, "ENGINE_PRODUCT_MANIFEST_INVALID");
        }
    }

    private String engineAnswerId(String productId) {
        return "answer." + ProductEngineCanonicalJson.sha256(productId).substring(0, 32);
    }
    private String engineCancelId(String productId) {
        return "cancel." + ProductEngineCanonicalJson.sha256(productId).substring(0, 32);
    }
    private String instructionKind(String value, String target) {
        String kind = value == null || value.isBlank() ? "INITIAL" : value.trim();
        if (!List.of("INITIAL", "SUPPLEMENT", "CORRECTION", "REPLACEMENT").contains(kind)) {
            throw new ProductEngineControlException(400, "ENGINE_INSTRUCTION_KIND_INVALID");
        }
        if ("INITIAL".equals(kind) && normalized(target) != null) {
            throw new ProductEngineControlException(400, "ENGINE_TARGET_NOT_ALLOWED");
        }
        if (!"INITIAL".equals(kind) && normalized(target) == null) {
            throw new ProductEngineControlException(400, "ENGINE_TARGET_REQUIRED");
        }
        return kind;
    }
    private String authoritativeInstruction(V2NaturalLanguageTurnRequest request, String kind) {
        String target = normalized(request.targetClientRequestId());
        String relation = "INITIAL".equals(kind) ? ""
                : "\n\nProduct continuation relation: " + kind + " of product turn " + target + ".";
        String skill = normalized(request.skillId()) == null ? ""
                : "\nRequested product skill reference: " + normalized(request.skillId()) + ".";
        return request.content() + relation + skill;
    }
    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private boolean terminal(String state) {
        return "succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }
    private ResponseStatusException publicFailure(ProductEngineControlException failure) {
        int status = failure.status() >= 400 && failure.status() <= 599 ? failure.status() : 500;
        return new ResponseStatusException(HttpStatus.valueOf(status), failure.code());
    }
}
