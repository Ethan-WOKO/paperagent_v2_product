package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.BoundedOutput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.Receipt;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDiffEntry;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspacePublishRequest;
import com.yanban.api.project.AutomaticProjectFileChange;
import com.yanban.api.project.ProjectRevisionOperation;
import com.yanban.api.project.ProjectRevisionOperationResponse;
import com.yanban.api.project.ProjectRevisionWorkflowService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentEnginePublicationGatewayTest {
    private static final String TASK = "task." + "1".repeat(64);
    private static final String VERSION = "3".repeat(64);
    private static final String BEFORE = "4".repeat(64);
    private static final String AFTER =
            "d67e2e944994496c8d8ec76eed0cf9f09679448d584b532bebf941852a37f5ed";
    private static final String RECEIPT = "receipt.compile.1";

    @Test
    void publishesOnlyReceiptBoundExactWorkspaceCandidate() {
        ObjectMapper json = new ObjectMapper();
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxGateway sandboxes = mock(AgentEngineSandboxGateway.class);
        ProjectRevisionWorkflowService revisions = mock(ProjectRevisionWorkflowService.class);
        WorkspaceDiffEntry entry = new WorkspaceDiffEntry(
                "MODIFY", "Sort.java", BEFORE, AFTER);
        AutomaticProjectFileChange change = new AutomaticProjectFileChange(
                "MODIFY", "Sort.java", BEFORE, AFTER, "changed");
        when(workspaces.publicationChanges(any(), any())).thenReturn(List.of(change));
        when(sandboxes.requireSuccessfulReceipt(any(), anyString())).thenReturn(receipt(AFTER));
        when(revisions.applyWorkspaceAutomatically(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ProjectRevisionOperationResponse(
                        41L, ProjectRevisionOperation.Type.APPLICATION,
                        ProjectRevisionOperation.Outcome.SUCCEEDED,
                        7L, VERSION, 8L, "6".repeat(64), null,
                        "7".repeat(64), List.of(0), List.of(), Instant.now()));
        AgentEnginePublicationGateway gateway = new AgentEnginePublicationGateway(
                workspaces, sandboxes, revisions, json);

        var result = gateway.publish(authority(), request(json, List.of(entry)));

        assertThat(result.baseProjectVersion()).isEqualTo(VERSION);
        assertThat(result.publishedProjectVersion()).isEqualTo("6".repeat(64));
        assertThat(result.publishedRevisionId()).isEqualTo(8L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(revisions).applyWorkspaceAutomatically(
                anyLong(), anyLong(), key.capture(), anyString(), anyString(), any());
        assertThat(key.getValue()).startsWith("react-engine-publish.");
    }

    @Test
    void rejectsReceiptThatDidNotValidateChangedBytes() {
        ObjectMapper json = new ObjectMapper();
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxGateway sandboxes = mock(AgentEngineSandboxGateway.class);
        ProjectRevisionWorkflowService revisions = mock(ProjectRevisionWorkflowService.class);
        WorkspaceDiffEntry entry = new WorkspaceDiffEntry(
                "MODIFY", "Sort.java", BEFORE, AFTER);
        when(workspaces.publicationChanges(any(), any())).thenReturn(List.of(
                new AutomaticProjectFileChange(
                        "MODIFY", "Sort.java", BEFORE, AFTER, "changed")));
        when(sandboxes.requireSuccessfulReceipt(any(), anyString()))
                .thenReturn(receipt("9".repeat(64)));
        AgentEnginePublicationGateway gateway = new AgentEnginePublicationGateway(
                workspaces, sandboxes, revisions, json);

        assertThatThrownBy(() -> gateway.publish(
                authority(), request(json, List.of(entry))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("WORKSPACE_PUBLICATION_RECEIPT_MISMATCH"));
        verify(revisions, never()).applyWorkspaceAutomatically(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void publishesPlainDocumentsWithExactLocalIntegrityProofWithoutSandbox() {
        ObjectMapper json = new ObjectMapper();
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxGateway sandboxes = mock(AgentEngineSandboxGateway.class);
        ProjectRevisionWorkflowService revisions = mock(ProjectRevisionWorkflowService.class);
        WorkspaceDiffEntry entry = new WorkspaceDiffEntry(
                "ADD", "notes/test.txt", null, AFTER);
        when(workspaces.publicationChanges(any(), any())).thenReturn(List.of(
                new AutomaticProjectFileChange(
                        "ADD", "notes/test.txt", null, AFTER, "changed")));
        when(revisions.applyWorkspaceAutomatically(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ProjectRevisionOperationResponse(
                        42L, ProjectRevisionOperation.Type.APPLICATION,
                        ProjectRevisionOperation.Outcome.SUCCEEDED,
                        7L, VERSION, 8L, "6".repeat(64), null,
                        "7".repeat(64), List.of(0), List.of(), Instant.now()));
        AgentEnginePublicationGateway gateway = new AgentEnginePublicationGateway(
                workspaces, sandboxes, revisions, json);

        var result = gateway.publish(authority(), documentRequest(json, List.of(entry)));

        assertThat(result.receiptRef()).startsWith("document-integrity.");
        verify(sandboxes, never()).requireSuccessfulReceipt(any(), anyString());
    }

    @Test
    void rejectsLocalDocumentProofForCodeChanges() {
        ObjectMapper json = new ObjectMapper();
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxGateway sandboxes = mock(AgentEngineSandboxGateway.class);
        ProjectRevisionWorkflowService revisions = mock(ProjectRevisionWorkflowService.class);
        WorkspaceDiffEntry entry = new WorkspaceDiffEntry(
                "MODIFY", "Sort.java", BEFORE, AFTER);
        when(workspaces.publicationChanges(any(), any())).thenReturn(List.of(
                new AutomaticProjectFileChange(
                        "MODIFY", "Sort.java", BEFORE, AFTER, "changed")));
        AgentEnginePublicationGateway gateway = new AgentEnginePublicationGateway(
                workspaces, sandboxes, revisions, json);

        assertThatThrownBy(() -> gateway.publish(
                authority(), documentRequest(json, List.of(entry))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "WORKSPACE_PUBLICATION_DOCUMENT_INTEGRITY_REJECTED"));
        verify(sandboxes, never()).requireSuccessfulReceipt(any(), anyString());
        verify(revisions, never()).applyWorkspaceAutomatically(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    private static WorkspacePublishRequest request(
            ObjectMapper json, List<WorkspaceDiffEntry> entries) {
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("receiptRef", RECEIPT);
        semantics.put("entries", entries);
        return new WorkspacePublishRequest("1.0", "call." + "a".repeat(40),
                ReactPlanCanonicalJson.digest(json, semantics), RECEIPT, entries);
    }

    private static WorkspacePublishRequest documentRequest(
            ObjectMapper json, List<WorkspaceDiffEntry> entries) {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("projectVersion", VERSION);
        proof.put("entries", entries);
        String receiptRef = "document-integrity."
                + ReactPlanCanonicalJson.digest(json, proof);
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("receiptRef", receiptRef);
        semantics.put("entries", entries);
        return new WorkspacePublishRequest("1.0", "call." + "b".repeat(40),
                ReactPlanCanonicalJson.digest(json, semantics), receiptRef, entries);
    }

    private static Receipt receipt(String inputHash) {
        BoundedOutput empty = new BoundedOutput("", false, 0);
        return new Receipt("1.0", RECEIPT, "execution.1", "SUCCEEDED", 0,
                empty, empty, "8".repeat(64),
                List.of(new ReceiptInput("Sort.java", inputHash, 7)),
                Instant.now(), Instant.now());
    }

    private static EngineTaskAuthority authority() {
        return new EngineTaskAuthority(TASK, "2".repeat(64),
                11, 12, 13, 14, VERSION, true, true, true,
                Instant.parse("2026-08-18T11:00:00Z"));
    }
}
