package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailability;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailabilityDocument;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnRequest;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnService;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeService;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnService;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnQueryService;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnResponse;
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
    private final V2NaturalLanguageTurnService v2NaturalLanguageTurns;
    private final V2AdaptiveTurnQueryService v2AdaptiveTurns;

    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes) {
        this(agentService, contextSnapshotService, v2LiteratureTurns,
                v2LiteratureOutcomes,
                V2ProductAvailability.enabledByDefault(), null, null);
    }

    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes,
                           V2ProductAvailability v2Availability) {
        this(agentService, contextSnapshotService, v2LiteratureTurns,
                v2LiteratureOutcomes, v2Availability, null);
    }

    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes,
                           V2ProductAvailability v2Availability,
                           V2NaturalLanguageTurnService v2NaturalLanguageTurns) {
        this(agentService, contextSnapshotService, v2LiteratureTurns,
                v2LiteratureOutcomes, v2Availability,
                v2NaturalLanguageTurns, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns,
                           V2LiteratureOutcomeService v2LiteratureOutcomes,
                           V2ProductAvailability v2Availability,
                           V2NaturalLanguageTurnService v2NaturalLanguageTurns,
                           V2AdaptiveTurnQueryService v2AdaptiveTurns) {
        this.agentService = agentService;
        this.contextSnapshotService = contextSnapshotService;
        this.v2LiteratureTurns = v2LiteratureTurns;
        this.v2LiteratureOutcomes = v2LiteratureOutcomes;
        this.v2Availability = v2Availability;
        this.v2NaturalLanguageTurns = v2NaturalLanguageTurns;
        this.v2AdaptiveTurns = v2AdaptiveTurns;
    }

    @GetMapping("/{sessionId}/v2/turns/{clientRequestId}")
    public V2AdaptiveTurnResponse getV2NaturalLanguageTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @PathVariable String clientRequestId) {
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        if (v2AdaptiveTurns == null) {
            throw new IllegalStateException(
                    "V2 adaptive execution is unavailable");
        }
        return v2AdaptiveTurns.get(
                currentUser.id(), sessionId, clientRequestId);
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

    @PostMapping("/{sessionId}/v2/turns")
    public V2NaturalLanguageTurnResponse sendV2NaturalLanguageTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @Valid @RequestBody V2NaturalLanguageTurnRequest request) {
        v2Availability.requireAvailable(
                V2ProductAvailability.NATURAL_LANGUAGE_TURN);
        if (v2NaturalLanguageTurns == null) {
            throw new IllegalStateException(
                    "V2 natural-language intake is unavailable");
        }
        return v2NaturalLanguageTurns.execute(
                currentUser.id(), sessionId, request);
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
}
