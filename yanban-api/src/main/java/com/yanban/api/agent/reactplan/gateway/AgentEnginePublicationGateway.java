package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.Receipt;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspacePublishRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspacePublishResult;
import com.yanban.api.project.AutomaticProjectFileChange;
import com.yanban.api.project.ProjectRevisionOperationResponse;
import com.yanban.api.project.ProjectRevisionWorkflowService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Deterministic non-model terminator for an exact validated Workspace Candidate. */
@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEnginePublicationGateway {
    private static final String DOCUMENT_INTEGRITY_PREFIX = "document-integrity.";
    private final AgentEngineWorkspaceGateway workspaces;
    private final AgentEngineSandboxGateway sandboxes;
    private final ProjectRevisionWorkflowService revisions;
    private final ObjectMapper json;

    AgentEnginePublicationGateway(
            AgentEngineWorkspaceGateway workspaces,
            AgentEngineSandboxGateway sandboxes,
            ProjectRevisionWorkflowService revisions,
            ObjectMapper json) {
        this.workspaces = workspaces;
        this.sandboxes = sandboxes;
        this.revisions = revisions;
        this.json = json;
    }

    WorkspacePublishResult publish(
            EngineTaskAuthority authority, WorkspacePublishRequest request) {
        validate(request);
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("receiptRef", request.receiptRef());
        semantics.put("entries", request.entries());
        if (!ReactPlanCanonicalJson.digest(json, semantics)
                .equals(request.requestDigest())) {
            throw EngineGatewayException.badRequest(
                    "WORKSPACE_PUBLICATION_DIGEST_INVALID");
        }
        List<AutomaticProjectFileChange> changes =
                workspaces.publicationChanges(authority, request.entries());
        if (request.receiptRef().startsWith(DOCUMENT_INTEGRITY_PREFIX)) {
            requireDocumentIntegrity(authority, request, changes);
        } else {
            Receipt receipt = sandboxes.requireSuccessfulReceipt(
                    authority, request.receiptRef());
            Map<String, String> proven = new LinkedHashMap<>();
            for (ReceiptInput input : receipt.inputs()) {
                if (proven.putIfAbsent(input.path(), input.sha256()) != null) {
                    throw EngineGatewayException.conflict(
                            "WORKSPACE_PUBLICATION_RECEIPT_CONFLICT");
                }
            }
            if (changes.stream().anyMatch(change ->
                    !change.afterSha256().equals(proven.get(change.path())))) {
                throw EngineGatewayException.conflict(
                        "WORKSPACE_PUBLICATION_RECEIPT_MISMATCH");
            }
        }
        String key = "react-engine-publish." + sha256(authority.taskId());
        ProjectRevisionOperationResponse published;
        try {
            published = revisions.applyWorkspaceAutomatically(
                    authority.userId(), authority.projectId(), key,
                    authority.projectVersion(), request.receiptRef(), changes);
        } catch (org.springframework.web.server.ResponseStatusException rejected) {
            throw new EngineGatewayException(
                    org.springframework.http.HttpStatus.valueOf(
                            rejected.getStatusCode().value()),
                    "WORKSPACE_PUBLICATION_REJECTED");
        }
        if (published.operationId() == null || published.resultRevisionId() == null
                || published.resultVersion() == null) {
            throw EngineGatewayException.conflict(
                    "WORKSPACE_PUBLICATION_RESULT_INVALID");
        }
        return new WorkspacePublishResult(
                "1.0", request.clientRequestId(), request.requestDigest(),
                published.operationId(), published.baseVersion(),
                published.resultVersion(), published.resultRevisionId(),
                request.receiptRef());
    }

    private void requireDocumentIntegrity(
            EngineTaskAuthority authority,
            WorkspacePublishRequest request,
            List<AutomaticProjectFileChange> changes) {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("projectVersion", authority.projectVersion());
        proof.put("entries", request.entries());
        String expected = DOCUMENT_INTEGRITY_PREFIX
                + ReactPlanCanonicalJson.digest(json, proof);
        if (!expected.equals(request.receiptRef())) {
            throw EngineGatewayException.conflict(
                    "WORKSPACE_PUBLICATION_DOCUMENT_PROOF_MISMATCH");
        }
        if (changes.isEmpty() || changes.stream().anyMatch(change ->
                !validDocumentIntegrityChange(change))) {
            throw EngineGatewayException.conflict(
                    "WORKSPACE_PUBLICATION_DOCUMENT_INTEGRITY_REJECTED");
        }
    }

    private static boolean validDocumentIntegrityChange(
            AutomaticProjectFileChange change) {
        if (change.serverGeneratedDocx()) {
            return change.path().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".docx");
        }
        return documentPath(change.path()) && change.content() != null
                && change.content().indexOf('\0') < 0;
    }

    private static boolean documentPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".markdown") || lower.endsWith(".rst")
                || lower.endsWith(".adoc") || lower.endsWith(".tex");
    }

    private static void validate(WorkspacePublishRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.clientRequestId() == null
                || !request.clientRequestId().matches("call\\.[A-Za-z0-9_-]{16,120}")
                || request.requestDigest() == null
                || !request.requestDigest().matches("[a-f0-9]{64}")
                || request.receiptRef() == null || request.receiptRef().isBlank()
                || request.entries() == null || request.entries().isEmpty()
                || request.entries().size() > 64) {
            throw EngineGatewayException.badRequest(
                    "WORKSPACE_PUBLICATION_REQUEST_INVALID");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
