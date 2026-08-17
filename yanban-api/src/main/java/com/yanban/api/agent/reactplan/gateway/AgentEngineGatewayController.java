package com.yanban.api.agent.reactplan.gateway;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileList;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileRead;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileReadRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.Receipt;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxSubmit;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxView;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDiff;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceWriteRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceWriteResult;
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
    private final AgentEngineRegisteredToolGateway registeredTools;

    AgentEngineGatewayController(AgentEngineTaskGrantService grants,
                                 AgentEngineWorkspaceGateway workspaces,
                                 AgentEngineSandboxGateway sandboxes,
                                 AgentEngineRegisteredToolGateway registeredTools) {
        this.grants = grants;
        this.workspaces = workspaces;
        this.sandboxes = sandboxes;
        this.registeredTools = registeredTools;
    }

    @GetMapping("/tools")
    AgentEngineGatewayDtos.RegisteredToolCatalog tools(
            @PathVariable String taskId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return registeredTools.catalog(grants.verify(authorization, taskId, false));
    }

    @PostMapping("/tool-calls")
    AgentEngineGatewayDtos.RegisteredToolResult invoke(
            @PathVariable String taskId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AgentEngineGatewayDtos.RegisteredToolCall request) {
        return registeredTools.invoke(grants.verify(authorization, taskId, false), request);
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

    @PostMapping("/workspace/write")
    WorkspaceWriteResult write(
            @PathVariable String taskId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody WorkspaceWriteRequest request) {
        return workspaces.write(grants.verifyWorkspaceWrite(authorization, taskId), request);
    }

    @GetMapping("/workspace/diff")
    WorkspaceDiff diff(
            @PathVariable String taskId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return workspaces.diff(grants.verifyWorkspaceWrite(authorization, taskId));
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
