package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentRequestCapability;
import com.yanban.api.agent.AgentOrchestrationRequirements;
import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentStrategy;
import com.yanban.api.agent.AgentStrategyReasonCode;
import com.yanban.api.agent.AgentStrategySelectionOrigin;
import com.yanban.api.agent.ResearchMaterialKind;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.worker.WorkerBudget;
import com.yanban.core.agent.worker.WorkerMaterialAssignment;
import com.yanban.core.agent.worker.WorkerMaterialType;
import com.yanban.core.agent.worker.WorkerServerAuthority;
import com.yanban.core.agent.worker.WorkerTaskAttestation;
import com.yanban.core.agent.worker.WorkerTaskAttestor;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ResearchRuntimeScope;
import com.yanban.core.research.ResearchToolContracts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Determines whether one server-auto Project request is eligible for the bounded two-Worker path. */
@Component
public class ControlledWorkerDispatchPlanner {

    static final String FINDING_KEY = "cross-material-observation";
    private static final int MIN_PARENT_STEPS = 5;
    private static final int MIN_PARENT_TOKENS = 1024;
    private static final long PAPER_BYTES_LIMIT = 2_000_000L;
    private static final long CODE_BYTES_LIMIT = 3_000_000L;
    private static final long CONFIG_BYTES_LIMIT = 5_000_000L;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "c", "cc", "cpp", "cxx",
            "h", "hpp", "m", "sh", "ps1");
    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            "json", "yaml", "yml", "toml", "xml", "csv", "tsv", "properties", "gradle");
    private static final Set<String> CONTRACT_READ_TOOLS = ResearchToolContracts.all().stream()
            .map(contract -> contract.definition().name()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final ProjectService projects;

    public ControlledWorkerDispatchPlanner(ProjectService projects) {
        this.projects = projects;
    }

    public Optional<ControlledWorkerDispatch> plan(AgentRuntimeRequest request,
                                                    AgentRequestCapability capability) {
        if (!structurallyEligible(request, capability)) return Optional.empty();
        ProjectManifestResponse manifest = projects.manifest(
                request.userId(), request.projectContext().projectId());
        ProjectVersionRef version = new ProjectVersionRef(manifest.version());
        List<ProjectFileEntry> manifestFiles = manifest.files().stream()
                .sorted(Comparator.comparing(ProjectFileEntry::path)).toList();
        Set<String> explicitScope = ProjectMaterialScope.explicitRelativePaths(request.userMessage());
        List<ProjectFileEntry> ordered = explicitScope.isEmpty() ? manifestFiles : manifestFiles.stream()
                .filter(file -> explicitScope.contains(ProjectMaterialScope.normalize(file.path())))
                .toList();

        List<ProjectFileEntry> paperFiles = ordered.stream().filter(this::isPaper).toList();
        List<ProjectFileEntry> codeFiles = ordered.stream().filter(this::isCode).toList();
        List<ProjectFileEntry> configFiles = ordered.stream().filter(this::isConfiguration).toList();
        Set<ResearchMaterialKind> requestedMaterials = request.orchestrationRequirements().materialRequirements()
                .stream().filter(requirement -> requirement.covered()).map(requirement -> requirement.material())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> parentTools = new LinkedHashSet<>(request.toolPolicy().allowedTools());
        boolean paperCovered = parentTools.contains(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        boolean codeCovered = requestedMaterials.contains(ResearchMaterialKind.CODE) && !codeFiles.isEmpty()
                && parentTools.contains(ResearchToolContracts.PROJECT_CODE_SYMBOLS);
        boolean configCovered = requestedMaterials.contains(ResearchMaterialKind.EXPERIMENT_CONFIG)
                && !configFiles.isEmpty()
                && parentTools.contains(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY);
        if (paperFiles.isEmpty() || !paperCovered || (!codeCovered && !configCovered)) {
            return Optional.empty();
        }
        int requiredToolCalls = 1 + (codeCovered ? 1 : 0) + (configCovered ? 1 : 0);
        if (request.toolPolicy().maxToolCalls() < requiredToolCalls) {
            return Optional.empty();
        }
        if (paperFiles.size() > 20 || (codeCovered && codeFiles.size() > 30)
                || (configCovered && configFiles.size() > 30)
                || sumBytes(paperFiles) > PAPER_BYTES_LIMIT
                || (codeCovered && sumBytes(codeFiles) > CODE_BYTES_LIMIT)
                || (configCovered && sumBytes(configFiles) > CONFIG_BYTES_LIMIT)) {
            return Optional.empty();
        }

        List<WorkerMaterialAssignment> paperAssignments = assignments(paperFiles, WorkerMaterialType.PAPER);
        List<WorkerMaterialAssignment> implementationAssignments = new ArrayList<>();
        List<String> implementationTools = new ArrayList<>();
        if (codeCovered) {
            implementationAssignments.addAll(assignments(codeFiles, WorkerMaterialType.CODE));
            implementationTools.add(ResearchToolContracts.PROJECT_CODE_SYMBOLS);
        }
        if (configCovered) {
            implementationAssignments.addAll(assignments(configFiles, WorkerMaterialType.CONFIGURATION));
            implementationTools.add(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY);
        }
        if (paperAssignments.size() + implementationAssignments.size() > WorkerBudget.HARD_MAX_INPUT_PATHS) {
            return Optional.empty();
        }

        List<String> authorityTools = parentTools.stream().filter(CONTRACT_READ_TOOLS::contains).sorted().toList();
        int paperToolCalls = request.toolPolicy().maxToolCalls() / 2;
        int implementationToolCalls = request.toolPolicy().maxToolCalls() - paperToolCalls;
        int workerSteps = request.maxSteps() - 1;
        int paperSteps = workerSteps / 2;
        int implementationSteps = workerSteps - paperSteps;
        int workerTokens = request.maxTokens() / 2;
        int paperTokens = workerTokens / 2;
        int implementationTokens = workerTokens - paperTokens;
        int parentTokens = request.maxTokens() - workerTokens;

        WorkerBudget parentBudget = new WorkerBudget(
                paperAssignments.size() + implementationAssignments.size(),
                request.toolPolicy().maxToolCalls(), 4, 1024,
                Math.max(1L, sumBytes(paperFiles)
                        + (codeCovered ? sumBytes(codeFiles) : 0L)
                        + (configCovered ? sumBytes(configFiles) : 0L)),
                256L * 1024L);
        AgentRunIdentity identity = new AgentRunIdentity("CONTROLLED_WORKER", request.traceId(), request.userId(),
                request.sessionId(), request.projectContext().projectId());
        ResearchRuntimeScope runtimeScope = new ResearchRuntimeScope(request.projectContext().projectId(),
                request.userId(), Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), version);
        WorkerServerAuthority authority = WorkerServerAuthority.serverResolved(
                identity, runtimeScope, authorityTools, parentBudget);
        String parentRunId = identity.runId();

        WorkerTaskAttestation paper = attest(authority, new WorkerTaskPacket(
                parentRunId + ":paper", parentRunId, version, paperAssignments,
                "Analyze the assigned LaTeX paper material for evidence relevant to a later cross-material comparison.",
                List.of("Inspect every assigned paper path", "Return only evidence-bound observations"),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), List.of(FINDING_KEY),
                childBudget(paperAssignments, paperToolCalls, sumBytes(paperFiles)), List.of()));
        WorkerTaskAttestation implementation = attest(authority, new WorkerTaskPacket(
                parentRunId + ":implementation", parentRunId, version, implementationAssignments,
                "Analyze the assigned code and configuration material for evidence relevant to a later cross-material comparison.",
                List.of("Inspect every assigned implementation path", "Return only evidence-bound observations"),
                implementationTools, List.of(FINDING_KEY),
                childBudget(implementationAssignments, implementationToolCalls,
                        (codeCovered ? sumBytes(codeFiles) : 0L)
                                + (configCovered ? sumBytes(configFiles) : 0L)), List.of()));

        Map<ProjectRelativePath, Long> sizes = new LinkedHashMap<>();
        Map<ProjectRelativePath, FileHash> hashes = new LinkedHashMap<>();
        paperFiles.forEach(file -> sizes.put(ProjectRelativePath.of(file.path()), file.sizeBytes()));
        paperFiles.forEach(file -> hashes.put(ProjectRelativePath.of(file.path()), new FileHash(file.sha256())));
        if (codeCovered) codeFiles.forEach(file -> sizes.put(ProjectRelativePath.of(file.path()), file.sizeBytes()));
        if (codeCovered) codeFiles.forEach(file -> hashes.put(
                ProjectRelativePath.of(file.path()), new FileHash(file.sha256())));
        if (configCovered) configFiles.forEach(file -> sizes.put(ProjectRelativePath.of(file.path()), file.sizeBytes()));
        if (configCovered) configFiles.forEach(file -> hashes.put(
                ProjectRelativePath.of(file.path()), new FileHash(file.sha256())));
        return Optional.of(new ControlledWorkerDispatch(authority, request.userId(), manifest.projectId(),
                request.sessionId(), parentRunId, version, List.of(
                new ControlledWorkerDispatch.Task(paper, paperSteps, paperTokens),
                new ControlledWorkerDispatch.Task(implementation, implementationSteps, implementationTokens)),
                sizes, hashes, request.maxSteps(), request.maxTokens(),
                request.toolPolicy().maxDuplicateToolCalls(), parentTokens));
    }

    private boolean structurallyEligible(AgentRuntimeRequest request, AgentRequestCapability capability) {
        if (request == null || capability != AgentRequestCapability.PROJECT_READ
                || request.strategy() != AgentStrategy.PLAN_EXECUTE || request.planId() != null
                || request.projectContext() == null || request.orchestrationRequirements() == null
                || !eligibleAutomaticPlanSelection(request.orchestrationRequirements())
                || !request.orchestrationRequirements().crossMaterial()
                || request.maxSteps() < MIN_PARENT_STEPS || request.maxTokens() == null
                || request.maxTokens() < MIN_PARENT_TOKENS || request.toolPolicy().maxToolCalls() < 2) {
            return false;
        }
        Set<ResearchMaterialKind> materials = request.orchestrationRequirements().materialRequirements().stream()
                .filter(requirement -> requirement.covered()).map(requirement -> requirement.material())
                .collect(java.util.stream.Collectors.toSet());
        return materials.contains(ResearchMaterialKind.PAPER_LATEX)
                && (materials.contains(ResearchMaterialKind.CODE)
                || materials.contains(ResearchMaterialKind.EXPERIMENT_CONFIG));
    }

    private boolean eligibleAutomaticPlanSelection(AgentOrchestrationRequirements requirements) {
        return (requirements.selectionOrigin() == AgentStrategySelectionOrigin.SERVER_AUTO
                && requirements.reasonCodes().contains(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN))
                || (requirements.selectionOrigin() == AgentStrategySelectionOrigin.LLM_ROUTER
                && requirements.reasonCodes().contains(AgentStrategyReasonCode.LLM_ROUTER_PLAN))
                || (requirements.selectionOrigin() == AgentStrategySelectionOrigin.ROUTER_FALLBACK
                && requirements.reasonCodes().contains(AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_PLAN)
                && requirements.reasonCodes().contains(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN));
    }

    private WorkerTaskAttestation attest(WorkerServerAuthority authority, WorkerTaskPacket packet) {
        return WorkerTaskAttestor.attestServerResolved(authority, packet);
    }

    private WorkerBudget childBudget(List<WorkerMaterialAssignment> assignments, int toolCalls, long bytes) {
        return new WorkerBudget(assignments.size(), toolCalls, 2, 512,
                Math.max(1L, bytes), 128L * 1024L);
    }

    private List<WorkerMaterialAssignment> assignments(List<ProjectFileEntry> files, WorkerMaterialType type) {
        return files.stream().map(file -> new WorkerMaterialAssignment(
                ProjectRelativePath.of(file.path()), type)).toList();
    }

    private long sumBytes(List<ProjectFileEntry> files) {
        try {
            long total = 0L;
            for (ProjectFileEntry file : files) total = Math.addExact(total, file.sizeBytes());
            return total;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private boolean isPaper(ProjectFileEntry file) { return "tex".equals(extension(file.path())); }
    private boolean isCode(ProjectFileEntry file) { return CODE_EXTENSIONS.contains(extension(file.path())); }
    private boolean isConfiguration(ProjectFileEntry file) {
        return CONFIG_EXTENSIONS.contains(extension(file.path()));
    }

    private String extension(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
