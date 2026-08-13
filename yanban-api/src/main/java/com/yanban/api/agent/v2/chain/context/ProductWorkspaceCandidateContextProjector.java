package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.project.ProjectService;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects module 8 from formal WorkspaceCandidate and Artifact facts. */
@Component
public final class ProductWorkspaceCandidateContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.WORKSPACE_AND_CANDIDATE;
    private static final String VERSION =
            "product-workspace-candidate-context-v1";
    private static final String PAGINATION =
            ProductProjectInputProjectionCodec.PAGINATION;
    private final ProductWorkspaceCandidateAuthority authority;

    public ProductWorkspaceCandidateContextProjector(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainFinalizationRepository finalization,
            CandidateChangeArtifactService candidates,
            ProjectService projects) {
        authority = new ProductWorkspaceCandidateAuthority(
                foundations, workflow, finalization, candidates, projects);
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            var snapshot = authority.read(request.buildingRevision());
            if (snapshot.empty()) return empty(request);
            var values = ProductWorkspaceCandidateProjectionValues.create(
                    request.buildingRevision(), request.requiredFields(MODULE),
                    snapshot);
            return ProductChainContextProjectionSupport.present(
                    MODULE, values.sourceVersion(), values.readBoundary(),
                    VERSION, PAGINATION, values.parameters(), values.fields(),
                    request.requiredFields(MODULE).toArray(String[]::new));
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw blocked("Workspace/Candidate authority query or digest failed");
        }
    }

    private ProductChainContextAuthorityProjection empty(
            ChainContextProjectionRequest request) {
        var revision = request.buildingRevision();
        String projectVersion = revision.projectVersion() == null
                ? "NONE" : revision.projectVersion();
        String digest = ProductChainContractProjectionCodec.sha256(
                "workspace=NONE\0candidateSequence=0\0" + revision.taskId()
                        + "\0" + projectVersion);
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of(
                        "workspaceConfirmationFingerprint",
                        ChainContextValue.text("NONE"),
                        "candidateBindingSequence", ChainContextValue.number(0),
                        "artifactFingerprintAndDiff",
                        ChainContextValue.object(Map.of(
                                "status", ChainContextValue.text("NONE"),
                                "absenceDigest",
                                ChainContextValue.text(digest)))),
                Map.of(
                        "projectVersion", ChainContextValue.text(
                                projectVersion),
                        "workspace", ChainContextValue.text("NONE"),
                        "candidate", ChainContextValue.text("NONE")),
                VERSION, PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest),
                        "role", ChainContextValue.text(
                                revision.role().name()),
                        "observedWorkspaceRef",
                        revision.workspaceId() == null
                                ? ChainContextValue.nil()
                                : ref(revision.workspaceId())),
                "workspace=NONE,candidateSequence=0");
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
