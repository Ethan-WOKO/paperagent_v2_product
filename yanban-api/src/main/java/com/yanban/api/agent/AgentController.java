package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnRequest;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnService;
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

    public AgentController(AgentService agentService,
                           AgentContextSnapshotService contextSnapshotService,
                           V2LiteratureTurnService v2LiteratureTurns) {
        this.agentService = agentService;
        this.contextSnapshotService = contextSnapshotService;
        this.v2LiteratureTurns = v2LiteratureTurns;
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

    @PostMapping("/{sessionId}/v2/literature-turns")
    public V2LiteratureTurnResponse sendV2LiteratureTurn(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @Valid @RequestBody V2LiteratureTurnRequest request) {
        return v2LiteratureTurns.execute(
                currentUser.id(), sessionId, request);
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
