package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects module 3 from one frozen ProjectVersion and exact object refs. */
@Component
public final class ProductProjectInputContextProjector
        implements ProductChainContextAuthorityReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.PROJECT_AND_INPUT_MATERIALS;
    private final ProductProjectInputAuthority authority;

    ProductProjectInputContextProjector(
            ProductProjectInputAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ProductProjectInputAuthority.Snapshot snapshot =
                    authority.read(request.buildingRevision());
            if (ProductDirectAnswerContextAuthority.isDirectAnswer(
                    request.buildingRevision())) {
                return presentDirectEmpty(request, snapshot);
            }
            return snapshot.hasProject()
                    ? present(request, snapshot) : empty(request, snapshot);
        } catch (ChainContextException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw blocked("Project input authority query or digest failed");
        }
    }

    private ProductChainContextAuthorityProjection presentDirectEmpty(
            ChainContextProjectionRequest request,
            ProductProjectInputAuthority.Snapshot source) {
        var revision = request.buildingRevision();
        String digest = ProductChainContractProjectionCodec.sha256(
                "project=NOT_REQUESTED\0" + source.task().taskId() + "\0"
                        + revision.instructionId());
        ChainContextValue none = ChainContextValue.object(Map.of(
                "projectAccess", ChainContextValue.text("NOT_REQUESTED"),
                "files", ChainContextValue.array(List.of())));
        Map<String, ChainContextValue> fields = new LinkedHashMap<>();
        for (String field : request.requiredFields(MODULE)) {
            fields.put(field, none);
        }
        return ProductChainContextProjectionSupport.present(
                MODULE,
                Map.of("projectVersion", ref(revision.projectVersion()),
                        "manifestFingerprint", ChainContextValue.text(digest),
                        "explicitInputRefVector",
                        ChainContextValue.array(List.of())),
                Map.of("projectAndVersion", ChainContextValue.object(Map.of(
                                "projectId", ChainContextValue.number(
                                        revision.projectId()),
                                "inputVersion", ref(revision.projectVersion()),
                                "access", ChainContextValue.text(
                                        "NOT_REQUESTED"))),
                        "completeManifestCut", ChainContextValue.object(Map.of(
                                "fileCount", ChainContextValue.number(0),
                                "digest", ChainContextValue.text(digest)))),
                ProductProjectInputProjectionCodec.VERSION,
                ProductProjectInputProjectionCodec.PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest),
                        "role", ChainContextValue.text("ANSWER")),
                Map.copyOf(fields),
                request.requiredFields(MODULE).toArray(String[]::new));
    }

    private ProductChainContextAuthorityProjection present(
            ChainContextProjectionRequest request,
            ProductProjectInputAuthority.Snapshot source) {
        var revision = request.buildingRevision();
        var task = source.task();
        var manifest = source.manifest();
        ChainContextValue root = ProductProjectInputProjectionCodec
                .manifestRoot(manifest);
        ChainContextValue explicit = ProductProjectExpandedBodyValues
                .projectFiles(manifest, source.explicitPaths(),
                        source.explicitBodies());
        ChainContextValue targets = ProductProjectExpandedBodyValues
                .projectFiles(manifest, source.targetPaths(),
                        source.targetBodies());
        ChainContextValue candidate = ProductProjectExpandedBodyValues
                .candidateFiles(source.candidate());
        ChainContextValue explicitIdentity = ProductProjectInputProjectionCodec
                .projectFiles(manifest, source.explicitPaths());
        ChainContextValue targetIdentity = ProductProjectInputProjectionCodec
                .projectFiles(manifest, source.targetPaths());
        ChainContextValue candidateIdentity = ProductProjectInputProjectionCodec
                .candidateFiles(source.candidate());
        ChainContextValue visibleInputVector = switch (revision.role()) {
            case PLANNER -> explicitIdentity;
            case EXECUTOR -> targetIdentity;
            case REFLECTOR, ANSWER -> candidateIdentity;
        };
        Map<String, ChainContextValue> fields = fields(
                revision.role(), manifest, root, explicit, targets,
                candidate, source);
        String manifestBodyDigest = ProductProjectInputProjectionCodec
                .manifestBodyDigest(manifest);
        return ProductChainContextProjectionSupport.present(
                MODULE,
                Map.of(
                        "projectVersion", ref(source.visibleVersion()),
                        "manifestFingerprint", ref(manifest.version()),
                        "explicitInputRefVector", visibleInputVector),
                Map.of(
                        "projectAndVersion", ChainContextValue.object(Map.of(
                                "projectId", ChainContextValue.number(
                                        task.projectId()),
                                "inputVersion", ref(revision.projectVersion()),
                                "visibleVersion", ref(
                                        source.visibleVersion()))),
                        "completeManifestCut", ChainContextValue.object(Map.of(
                                "manifestFingerprint", ref(manifest.version()),
                                "manifestBodySha256", ref(manifestBodyDigest),
                                "fileCount", ChainContextValue.number(
                                        manifest.files().size())))),
                ProductProjectInputProjectionCodec.VERSION,
                ProductProjectInputProjectionCodec.PAGINATION,
                Map.of("manifestAuthorityRef", ref(
                                ProductProjectInputProjectionCodec.manifestRef(
                                        task.projectId())),
                        "manifestAuthorityDigest", ref(manifestBodyDigest),
                        "role", ChainContextValue.text(revision.role().name())),
                fields, request.requiredFields(MODULE).toArray(String[]::new));
    }

    private ProductChainContextAuthorityProjection empty(
            ChainContextProjectionRequest request,
            ProductProjectInputAuthority.Snapshot source) {
        String digest = ProductChainContractProjectionCodec.sha256(
                "project=NONE\0input=[]\0" + source.task().taskId());
        return ProductChainContextProjectionSupport.empty(
                MODULE,
                Map.of("projectVersion", ChainContextValue.text("NONE"),
                        "manifestFingerprint", ChainContextValue.text(digest),
                        "explicitInputRefVector",
                        ChainContextValue.array(List.of())),
                Map.of("projectAndVersion", ChainContextValue.text("NONE"),
                        "completeManifestCut", ChainContextValue.object(Map.of(
                                "fileCount", ChainContextValue.number(0),
                                "digest", ChainContextValue.text(digest)))),
                ProductProjectInputProjectionCodec.VERSION,
                ProductProjectInputProjectionCodec.PAGINATION,
                Map.of("absenceDigest", ChainContextValue.text(digest)),
                "project=NONE,input=[]");
    }

    private static Map<String, ChainContextValue> fields(
            ChainRole role,
            com.yanban.api.project.ProjectManifestResponse manifest,
            ChainContextValue root, ChainContextValue explicit,
            ChainContextValue targets, ChainContextValue candidate,
            ProductProjectInputAuthority.Snapshot source) {
        Map<String, ChainContextValue> values = new LinkedHashMap<>();
        switch (role) {
            case PLANNER -> {
                values.put("project.version", version(manifest));
                values.put("project.manifest.complete", root);
                values.put("project.explicitInputExpansion", explicit);
            }
            case EXECUTOR -> {
                values.put("project.version", version(manifest));
                values.put("project.manifest.complete", root);
                values.put("project.currentStepObjects", source.step() == null
                        ? ChainContextValue.nil()
                        : ProductChainContractProjectionCodec.planStep(
                                source.step()).value());
                values.put("project.targetAndModifiedFileExpansion",
                        ChainContextValue.object(Map.of(
                                "targets", targets,
                                "candidateChanges", candidate)));
            }
            case REFLECTOR -> {
                values.put("project.version", version(manifest));
                values.put("project.manifest.complete", root);
                values.put("project.reviewedAndDiffAffectedExpansion",
                        candidate);
            }
            case ANSWER -> {
                var outcome = Objects.requireNonNull(source.outcome());
                ChainContextValue delivery = ChainContextValue.object(Map.of(
                        "taskOutcomeRef", ref(outcome.outcomeId()),
                        "publishedProjectVersion",
                        outcome.publishedProjectVersion() == null
                                ? ChainContextValue.nil()
                                : ref(outcome.publishedProjectVersion()),
                        "finalArtifactId", outcome.finalArtifactId() == null
                                ? ChainContextValue.nil()
                                : ChainContextValue.number(
                                        outcome.finalArtifactId()),
                        "candidateFiles", candidate));
                values.put("project.finalInputAndDeliveryObjects", delivery);
                values.put("project.artifactOrCandidate", candidate);
                values.put("project.deliveryManifest", root);
                values.put("project.userVisibleBodyExpansion", candidate);
            }
        }
        return Map.copyOf(values);
    }

    private static ChainContextValue version(
            com.yanban.api.project.ProjectManifestResponse value) {
        return ChainContextValue.object(Map.of(
                "projectId", ChainContextValue.number(value.projectId()),
                "version", ref(value.version())));
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static ChainContextException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
