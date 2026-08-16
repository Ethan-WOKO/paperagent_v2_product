package com.yanban.api.agent.reactplan.gateway;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileList;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileRead;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileReadRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.Receipt;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxSubmit;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/internal/v1/agent-engine/tasks/{taskId}")
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineGatewayController {
    private final AgentEngineTaskGrantService grants;
    private final AgentEngineWorkspaceGateway workspaces;
    private final AgentEngineSandboxGateway sandboxes;

    AgentEngineGatewayController(AgentEngineTaskGrantService grants,
                                 AgentEngineWorkspaceGateway workspaces,
                                 AgentEngineSandboxGateway sandboxes) {
        this.grants = grants;
        this.workspaces = workspaces;
        this.sandboxes = sandboxes;
    }

    @GetMapping("/workspace/files")
    FileList list(@PathVariable String taskId,
                  @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return workspaces.list(grants.verify(authorization, taskId, false));
    }

    @PostMapping("/workspace/read")
    FileRead read(@PathVariable String taskId,
                  @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                  @RequestBody FileReadRequest request) {
        return workspaces.read(grants.verify(authorization, taskId, false), request);
    }

    @PostMapping("/sandbox-executions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SandboxView submit(@PathVariable String taskId,
                       @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @RequestBody SandboxSubmit request) {
        return sandboxes.submit(grants.verify(authorization, taskId, true), request);
    }

    @GetMapping("/sandbox-executions/{clientRequestId}")
    SandboxView status(@PathVariable String taskId,
                       @PathVariable String clientRequestId,
                       @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return sandboxes.status(grants.verify(authorization, taskId, true), clientRequestId);
    }

    @GetMapping("/receipts/{receiptRef}")
    Receipt receipt(@PathVariable String taskId,
                    @PathVariable String receiptRef,
                    @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return sandboxes.receipt(grants.verify(authorization, taskId, true), receiptRef);
    }
}
