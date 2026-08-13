package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailability;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailabilityDocument;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnRequest;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnService;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeService;
import com.yanban.api.agent.v2.chain.api.ProjectChainTurnApi;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnListItem;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnResponse;
import com.yanban.api.agent.v2.chain.api.V2TurnCancelRequest;
import com.yanban.api.agent.v2.chain.api.V2TurnCommandRequest;
import com.yanban.api.agent.v2.chain.api.V2TurnCommandResponse;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/sessions")
public class AgentController {

    private final AgentService agentService;
    private final AgentContextSnapshotService contextSnapshotService;
    private final V2LiteratureTurnService v2LiteratureTurns;
    private final V2LiteratureOutcomeService v2LiteratureOutcomes;
    private final V2ProductAvailability v2Availability;
    private final ProjectChainTurnApi projectChainTurns;

    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes) {
        this(agentService, contextSnapshotService, v2LiteratureTurns,
                v2LiteratureOutcomes,
                V2ProductAvailability.enabledByDefault(), null);
    }

    /**
     * Compatibility constructor for the V2-only controller surface. The
     * workspace chat endpoints require {@link AgentService}; V2 endpoint
     * tests and callers that never use that surface can retain the stable
     * session-service dependency used before workspace chat was restored.
     */
    public AgentController(AgentSessionService sessionService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes,
                           V2ProductAvailability v2Availability) {
        java.util.Objects.requireNonNull(sessionService, "sessionService");
        this.agentService = null;
        this.contextSnapshotService = null;
        this.v2LiteratureTurns = v2LiteratureTurns;
        this.v2LiteratureOutcomes = v2LiteratureOutcomes;
        this.v2Availability = v2Availability;
        this.projectChainTurns = null;
    }

    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes,
                           V2ProductAvailability v2Availability) {
        this(agentService, contextSnapshotService, v2LiteratureTurns,
                v2LiteratureOutcomes, v2Availability, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes,
                           V2ProductAvailability v2Availability,
                           ProjectChainTurnApi projectChainTurns) {
        this.agentService = agentService;
        this.contextSnapshotService = contextSnapshotService;
        this.v2LiteratureTurns = v2LiteratureTurns;
        this.v2LiteratureOutcomes = v2LiteratureOutcomes;
        this.v2Availability = v2Availability;
        this.projectChainTurns = projectChainTurns;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentSessionResponse createSession(@AuthenticationPrincipal JwtUser currentUser,
                                              @Valid @RequestBody CreateSessionRequest request) {
        return agentService.createSession(currentUser.id(), request);
    }

    @GetMapping
    public List<AgentSessionResponse> listSessions(@AuthenticationPrincipal JwtUser currentUser) {
        return agentService.listSessions(currentUser.id());
    }

    @PatchMapping("/{sessionId}")
    public AgentSessionResponse updateSession(@AuthenticationPrincipal JwtUser currentUser,
                                              @PathVariable Long sessionId,
                                              @Valid @RequestBody UpdateSessionRequest request) {
        return agentService.updateSession(currentUser.id(), sessionId, request);
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@AuthenticationPrincipal JwtUser currentUser,
                              @PathVariable Long sessionId) {
        agentService.deleteSession(currentUser.id(), sessionId);
    }

    @GetMapping("/{sessionId}/messages")
    public List<AgentMessageResponse> listMessages(@AuthenticationPrincipal JwtUser currentUser,
                                                   @PathVariable Long sessionId,
                                                   @RequestParam(defaultValue = "50") Integer limit,
                                                   @RequestParam(required = false) Long beforeId,
                                                   @RequestParam(defaultValue = "chat") String view) {
        return agentService.listMessages(currentUser.id(), sessionId, limit, beforeId, view);
    }

    @PostMapping("/{sessionId}/messages")
    public SendMessageResponse sendMessage(@AuthenticationPrincipal JwtUser currentUser,
                                           @PathVariable Long sessionId,
                                           @Valid @RequestBody SendMessageRequest request) {
        return agentService.sendMessage(currentUser.id(), sessionId, request);
    }

    @GetMapping("/v2/capabilities")
    public V2ProductAvailabilityDocument v2Capabilities(
            @AuthenticationPrincipal JwtUser currentUser) {
        java.util.Objects.requireNonNull(currentUser, "currentUser");
        return v2Availability.document();
    }

    @PostMapping("/{sessionId}/v2/literature-turns")
    public V2LiteratureTurnResponse sendV2LiteratureTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @Valid @RequestBody V2LiteratureTurnRequest request) {
        v2Availability.requireAvailable(
                V2ProductAvailability.LITERATURE_SEARCH);
        return v2LiteratureTurns.execute(
                currentUser.id(), sessionId, request);
    }

    @PostMapping("/{sessionId}/v2/turns")
    public V2NaturalLanguageTurnResponse startV2ProjectTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @Valid @RequestBody V2NaturalLanguageTurnRequest request) {
        requireProjectChain();
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        return projectChainTurns.start(currentUser.id(), sessionId, request);
    }

    @PostMapping("/{sessionId}/v2/turns/{targetClientRequestId}/pending-items/{gapId}/reply")
    public V2TurnCommandResponse replyV2ProjectTurnGap(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @PathVariable String targetClientRequestId,
            @PathVariable String gapId,
            @Valid @RequestBody V2TurnCommandRequest request) {
        requireProjectChain();
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        return projectChainTurns.reply(currentUser.id(), sessionId,
                targetClientRequestId, gapId, request);
    }

    @PostMapping("/{sessionId}/v2/turns/{targetClientRequestId}/cancel")
    public V2TurnCommandResponse cancelV2ProjectTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @PathVariable String targetClientRequestId,
            @Valid @RequestBody V2TurnCancelRequest request) {
        requireProjectChain();
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        return projectChainTurns.cancel(currentUser.id(), sessionId,
                targetClientRequestId, request);
    }

    @GetMapping("/{sessionId}/v2/turns/{clientRequestId}")
    public V2ProjectTurnResponse getV2ProjectTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @PathVariable String clientRequestId) {
        requireProjectChain();
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        return projectChainTurns.get(
                currentUser.id(), sessionId, clientRequestId);
    }

    @GetMapping("/{sessionId}/v2/turns")
    public List<V2ProjectTurnListItem> listV2ProjectTurns(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "50") Integer limit) {
        requireProjectChain();
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        return projectChainTurns.list(currentUser.id(), sessionId,
                limit == null ? 50 : limit);
    }

    @GetMapping("/{sessionId}/v2/literature-turns/{clientRequestId}")
    public V2LiteratureOutcomeResponse getV2LiteratureTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @PathVariable String clientRequestId) {
        v2Availability.requireAvailable(
                V2ProductAvailability.LITERATURE_SEARCH);
        return v2LiteratureOutcomes.get(
                currentUser.id(), sessionId, clientRequestId);
    }

    @PostMapping("/{sessionId}/v2/literature-turns/{clientRequestId}/cancel")
    public V2LiteratureOutcomeResponse cancelV2LiteratureTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @PathVariable String clientRequestId) {
        v2Availability.requireAvailable(
                V2ProductAvailability.LITERATURE_SEARCH);
        return v2LiteratureOutcomes.cancel(
                currentUser.id(), sessionId, clientRequestId);
    }

    @GetMapping("/{sessionId}/context-snapshots")
    public List<AgentContextSnapshotResponse> listContextSnapshots(@AuthenticationPrincipal JwtUser currentUser,
                                                                   @PathVariable Long sessionId,
                                                                   @RequestParam(defaultValue = "20") Integer limit) {
        return contextSnapshotService.listSessionSnapshots(currentUser.id(), sessionId, limit);
    }

    @GetMapping("/{sessionId}/turns/{turnId}/context-snapshot")
    public AgentContextSnapshotResponse getContextSnapshot(@AuthenticationPrincipal JwtUser currentUser,
                                                          @PathVariable Long sessionId,
                                                          @PathVariable Long turnId) {
        return contextSnapshotService.getTurnSnapshot(currentUser.id(), sessionId, turnId);
    }

    private void requireProjectChain() {
        if (projectChainTurns == null) {
            throw new IllegalStateException("Project chain API is unavailable");
        }
    }
}
