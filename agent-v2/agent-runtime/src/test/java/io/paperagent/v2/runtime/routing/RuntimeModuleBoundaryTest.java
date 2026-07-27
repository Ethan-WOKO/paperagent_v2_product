package io.paperagent.v2.runtime.routing;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModuleBoundaryTest {
    private static final String PERSISTENCE_PREFIX =
            "io.paperagent.v2.persistence";
    private static final String WORKSPACE_PREFIX =
            "io.paperagent.v2.workspace";
    private static final Set<String> ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.PlanBootstrapRepository;",
            "import io.paperagent.v2.persistence.PersistenceResult;",
            "import io.paperagent.v2.persistence.PersistedPlanBootstrap;");
    private static final Set<String> ALLOWED_EXECUTION_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.PersistenceResult;",
            "import io.paperagent.v2.persistence.PersistedPlanBootstrap;",
            "import io.paperagent.v2.persistence.PersistenceOutcome;",
            "import io.paperagent.v2.persistence.PersistenceFailure;");
    private static final Set<String>
            ALLOWED_EXECUTION_START_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRequest;",
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence.LeaseRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStart;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedPlanBootstrap;",
                    "import io.paperagent.v2.persistence.PersistenceFailure;",
                    "import io.paperagent.v2.persistence.PersistenceOutcome;",
                    "import io.paperagent.v2.persistence.PersistenceResult;");
    private static final Set<String>
            ALLOWED_RECOVERY_MATERIALIZATION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartReady;");
    private static final Set<String>
            ALLOWED_RECOVERY_COMPOSITION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRecoveryRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRecoverySnapshot;",
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRequest;",
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence.LeaseRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStart;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartReady;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartCommitted;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceErrorCode;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceFailure;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceOutcome;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceResult;");
    private static final Set<String>
            ALLOWED_STEP_RECOVERY_COMPOSITION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence.LeaseRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepRecoveryActive;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceErrorCode;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceFailure;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceOutcome;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceResult;",
                    "import io.paperagent.v2.persistence"
                            + ".StepRecoveryRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".StepRecoverySnapshot;");
    private static final Set<String>
            ALLOWED_CONTEXT_COMPOSITION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRecoveryRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRecoverySnapshot;",
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence.LeaseRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartCommitted;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartReady;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedPlanExecutionContextConfirmed;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedPlanExecutionContextReserved;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceErrorCode;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceFailure;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceOutcome;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceResult;",
                    "import io.paperagent.v2.persistence"
                            + ".PlanExecutionContextConfirmationRequest;",
                    "import io.paperagent.v2.persistence"
                            + ".PlanExecutionContextRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PlanExecutionContextReservationRequest;",
                    "import io.paperagent.v2.persistence"
                            + ".PlanExecutionContextSnapshot;");
    private static final Set<String>
            ALLOWED_CONTEXT_COMPOSITION_WORKSPACE_IMPORTS = Set.of(
                    "import io.paperagent.v2.workspace"
                            + ".VerifiedWorkspaceMaterialization;",
                    "import io.paperagent.v2.workspace.WorkspaceErrorCode;",
                    "import io.paperagent.v2.workspace.WorkspaceException;",
                    "import io.paperagent.v2.workspace.WorkspacePort;");
    private static final Set<String>
            ALLOWED_ACTIVATION_MATERIALIZATION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartCommitted;");
    private static final Set<String>
            ALLOWED_ACTIVATION_COMPOSITION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence.LeaseRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartCommitted;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepActivation;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistenceFailure;",
                    "import io.paperagent.v2.persistence.PersistenceOutcome;",
                    "import io.paperagent.v2.persistence.PersistenceResult;",
                    "import io.paperagent.v2.persistence"
                            + ".StepActivationRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".StepActivationRequest;");
    private static final Set<String>
            ALLOWED_INTERRUPTION_MATERIALIZATION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepActivation;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepRecoveryActive;",
                    "import io.paperagent.v2.persistence.StepPauseRequest;",
                    "import io.paperagent.v2.persistence.StepFailRequest;",
                    "import io.paperagent.v2.persistence.StepCancelRequest;",
                    "import io.paperagent.v2.persistence"
                            + ".StepInterruptionKind;",
                    "import io.paperagent.v2.persistence"
                            + ".VersionedCheckpoint;");
    private static final Set<String>
            ALLOWED_INTERRUPTION_COMPOSITION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepInterruption;",
                    "import io.paperagent.v2.persistence.PersistenceFailure;",
                    "import io.paperagent.v2.persistence.PersistenceOutcome;",
                    "import io.paperagent.v2.persistence.PersistenceResult;",
                    "import io.paperagent.v2.persistence.StepPauseRequest;",
                    "import io.paperagent.v2.persistence.StepFailRequest;",
                    "import io.paperagent.v2.persistence.StepCancelRequest;",
                    "import io.paperagent.v2.persistence"
                            + ".StepInterruptionKind;",
                    "import io.paperagent.v2.persistence"
                            + ".StepInterruptionRepository;");
    private static final Set<String>
            ALLOWED_COMPLETION_MATERIALIZATION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepActivation;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedStepRecoveryActive;",
                    "import io.paperagent.v2.persistence"
                            + ".StepCompletionRequest;",
                    "import io.paperagent.v2.persistence"
                            + ".VersionedCheckpoint;");
    private static final Set<String> ALLOWED_KERNEL_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.EffectIntentRepository;",
            "import io.paperagent.v2.persistence.EffectIntentRequest;",
            "import io.paperagent.v2.persistence.LeaseRecord;",
            "import io.paperagent.v2.persistence.PersistedEffectIntent;",
            "import io.paperagent.v2.persistence.PersistedStepActivation;",
            "import io.paperagent.v2.persistence.PersistedStepRecoveryActive;",
            "import io.paperagent.v2.persistence.PersistenceFailure;",
            "import io.paperagent.v2.persistence.PersistenceResult;",
            "import io.paperagent.v2.persistence.VersionedCheckpoint;");
    private static final Set<String> ALLOWED_LOOP_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.PersistenceFailure;",
            "import io.paperagent.v2.persistence.PersistedEffectIntent;");
    private static final Set<String> ALLOWED_REPLAN_COMPOSITION_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.ActiveStepReplanRepository;",
            "import io.paperagent.v2.persistence.ActiveStepReplanRequest;",
            "import io.paperagent.v2.persistence.LeaseRecord;",
            "import io.paperagent.v2.persistence.PersistedActiveStepReplan;",
            "import io.paperagent.v2.persistence.PersistedEffectIntent;",
            "import io.paperagent.v2.persistence.PersistedStepActivation;",
            "import io.paperagent.v2.persistence.PersistedStepRecoveryActive;",
            "import io.paperagent.v2.persistence.PersistenceFailure;",
            "import io.paperagent.v2.persistence.PersistenceOutcome;",
            "import io.paperagent.v2.persistence.PersistenceResult;");
    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            PERSISTENCE_PREFIX,
            WORKSPACE_PREFIX,
            "io.paperagent.v2.sandbox",
            "io.paperagent.v2.providers",
            "io.paperagent.v2.app",
            "org.springframework.",
            "com.fasterxml.",
            "okhttp",
            "retrofit",
            "openai",
            "anthropic",
            "e2b",
            "paperagent.v1",
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "Candidate",
            "java.net.",
            "java.net.http",
            "java.io.",
            "java.nio.file.",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "System.getenv",
            "System.getProperty",
            "SecretRef",
            "\".env",
            "System.currentTimeMillis",
            "Instant.now",
            "Clock.",
            "UUID.randomUUID",
            "Thread.sleep");

    @Test
    void productionDependsOnlyOnFrozenRuntimeDependenciesAndJdk()
            throws Exception {
        Path module = moduleDirectory();
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(module.resolve("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        List<String> productionDependencies = new ArrayList<>();
        List<String> testDependencies = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String coordinate =
                    text(dependency, "groupId") + ":" + text(dependency, "artifactId");
            if ("test".equals(text(dependency, "scope"))) {
                testDependencies.add(coordinate);
            } else {
                productionDependencies.add(coordinate);
            }
        }
        assertEquals(
                List.of(
                        "io.paperagent.v2:agent-contracts",
                        "io.paperagent.v2:agent-persistence",
                        "io.paperagent.v2:agent-workspace"),
                productionDependencies);
        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), testDependencies);

        Path sourceRoot = module.resolve("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot));
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                Set<String> allowedPersistenceImports =
                        allowedPersistenceImports(sourceRoot, sourcePath);
                Set<String> allowedWorkspaceImports =
                        allowedWorkspaceImports(sourceRoot, sourcePath);
                for (String line : Files.readAllLines(sourcePath)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(
                                trimmed.startsWith("import java.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.contracts.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.runtime.")
                                        || allowedPersistenceImports.contains(trimmed)
                                        || allowedWorkspaceImports.contains(trimmed),
                                () -> sourcePath + " crosses production boundary: " + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void productionHasNoForbiddenRuntimeSideEffectsOrV1Markers() throws Exception {
        Path sourceRoot = moduleDirectory().resolve("src/main/java");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(sourcePath).toLowerCase();
                for (String marker : FORBIDDEN_SOURCE_MARKERS) {
                    if (marker.equals(PERSISTENCE_PREFIX)
                            || marker.equals(WORKSPACE_PREFIX)) {
                        continue;
                    }
                    assertFalse(
                            source.contains(marker.toLowerCase()),
                            () -> sourcePath + " contains forbidden marker " + marker);
                }
                for (String line : Files.readAllLines(sourcePath)) {
                    if (line.contains(PERSISTENCE_PREFIX)) {
                        String trimmed = line.trim();
                        assertTrue(
                                allowedPersistenceImports(sourceRoot, sourcePath)
                                        .contains(trimmed),
                                () -> sourcePath
                                        + " contains non-allowlisted persistence reference: "
                                        + trimmed);
                    }
                    if (line.contains(WORKSPACE_PREFIX)) {
                        String trimmed = line.trim();
                        assertTrue(
                                allowedWorkspaceImports(
                                        sourceRoot,
                                        sourcePath).contains(trimmed),
                                () -> sourcePath
                                        + " contains non-allowlisted"
                                        + " workspace reference: "
                                        + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void persistenceImportsArePackageExactAndFailClosed() {
        Path sourceRoot = Path.of("src", "main", "java");
        Path bootstrapSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "bootstrap",
                "Bootstrap.java"));
        Path executionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "Gate.java"));
        Path executionStartSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "start",
                "Starter.java"));
        Path recoveryMaterializationSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "materialization",
                "Materializer.java"));
        Path recoveryCompositionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "composition",
                "Recoverer.java"));
        Path stepRecoveryCompositionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "composition",
                "StepRecoverer.java"));
        Path contextCompositionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "context",
                "composition",
                "Composer.java"));
        Path activationMaterializationSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "activation",
                "materialization",
                "Materializer.java"));
        Path activationCompositionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "activation",
                "composition",
                "Composer.java"));
        Path interruptionMaterializationSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "interruption",
                "materialization",
                "Materializer.java"));
        Path interruptionMaterializationSubpackageSource =
                sourceRoot.resolve(Path.of(
                        "io",
                        "paperagent",
                        "v2",
                        "runtime",
                        "execution",
                        "interruption",
                        "materialization",
                        "internal",
                        "Escape.java"));
        Path interruptionCompositionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "interruption",
                "composition",
                "Composer.java"));
        Path completionMaterializationSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "completion",
                "materialization",
                "Materializer.java"));
        Path completionMaterializationSiblingSource =
                sourceRoot.resolve(Path.of(
                        "io",
                        "paperagent",
                        "v2",
                        "runtime",
                        "execution",
                        "completion",
                        "composition",
                        "Escape.java"));
        Path kernelSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "kernel",
                "Kernel.java"));
        Path loopSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "loop",
                "Loop.java"));
        Path replanCompositionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "replan",
                "composition",
                "Composer.java"));
        Path activationMaterializationSubpackageSource =
                sourceRoot.resolve(Path.of(
                        "io",
                        "paperagent",
                        "v2",
                        "runtime",
                        "execution",
                        "activation",
                        "materialization",
                        "internal",
                        "Escape.java"));
        Path otherRuntimeSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "planning",
                "Planner.java"));

        for (String allowed : ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS) {
            assertTrue(allowedPersistenceImports(sourceRoot, bootstrapSource)
                    .contains(allowed));
        }
        for (String allowed : ALLOWED_EXECUTION_PERSISTENCE_IMPORTS) {
            assertTrue(allowedPersistenceImports(sourceRoot, executionSource)
                    .contains(allowed));
        }
        for (String allowed
                : ALLOWED_EXECUTION_START_PERSISTENCE_IMPORTS) {
            assertTrue(
                    allowedPersistenceImports(
                            sourceRoot,
                            executionStartSource).contains(allowed));
        }
        assertEquals(
                ALLOWED_RECOVERY_MATERIALIZATION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        recoveryMaterializationSource));
        assertEquals(
                ALLOWED_RECOVERY_COMPOSITION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        recoveryCompositionSource));
        assertEquals(
                ALLOWED_STEP_RECOVERY_COMPOSITION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        stepRecoveryCompositionSource));
        assertEquals(
                ALLOWED_CONTEXT_COMPOSITION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        contextCompositionSource));
        assertEquals(
                ALLOWED_ACTIVATION_MATERIALIZATION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        activationMaterializationSource));
        assertEquals(
                ALLOWED_ACTIVATION_COMPOSITION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(sourceRoot, activationCompositionSource));
        assertEquals(
                ALLOWED_INTERRUPTION_MATERIALIZATION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionMaterializationSource));
        assertEquals(
                ALLOWED_INTERRUPTION_MATERIALIZATION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionMaterializationSubpackageSource));
        assertEquals(
                ALLOWED_INTERRUPTION_COMPOSITION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionCompositionSource));
        assertTrue(
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionCompositionSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".StepFailRequest;"));
        assertEquals(
                ALLOWED_COMPLETION_MATERIALIZATION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        completionMaterializationSource));
        assertTrue(
                allowedPersistenceImports(
                        sourceRoot,
                        completionMaterializationSiblingSource)
                        .isEmpty());
        assertEquals(
                ALLOWED_KERNEL_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(sourceRoot, kernelSource));
        assertEquals(
                ALLOWED_LOOP_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(sourceRoot, loopSource));
        assertEquals(
                ALLOWED_REPLAN_COMPOSITION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(sourceRoot, replanCompositionSource));
        assertTrue(
                allowedPersistenceImports(
                        sourceRoot,
                        activationMaterializationSubpackageSource).isEmpty());
        assertEquals(
                ALLOWED_CONTEXT_COMPOSITION_WORKSPACE_IMPORTS,
                allowedWorkspaceImports(
                        sourceRoot,
                        contextCompositionSource));
        assertTrue(
                allowedWorkspaceImports(
                        sourceRoot,
                        recoveryCompositionSource).isEmpty());
        assertTrue(
                allowedWorkspaceImports(
                        sourceRoot,
                        activationMaterializationSource).isEmpty());
        assertTrue(
                allowedWorkspaceImports(
                        sourceRoot,
                        activationMaterializationSubpackageSource).isEmpty());

        assertFalse(allowedPersistenceImports(sourceRoot, bootstrapSource)
                .contains(
                        "import io.paperagent.v2.persistence.PersistenceOutcome;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence.PlanBootstrapRepository;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence.InMemoryPersistence;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence.LeaseRepository;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence"
                                + ".ExecutionStartRepository;"));
        assertFalse(
                allowedPersistenceImports(sourceRoot, executionStartSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".InMemoryPersistence;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        recoveryMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".PersistedExecutionStartCommitted;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        recoveryCompositionSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".InMemoryPersistence;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        stepRecoveryCompositionSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".InMemoryPersistence;"));
        assertFalse(
                allowedPersistenceImports(sourceRoot, executionSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".PersistedExecutionStartReady;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        activationMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".StepActivationRequest;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        activationMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence.*;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        activationMaterializationSource)
                        .contains(
                                "import static io.paperagent.v2.persistence"
                                        + ".PersistenceOutcome.APPLIED;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        activationMaterializationSource)
                        .contains(
                                "return io.paperagent.v2.persistence"
                                        + ".PersistenceResult.applied(value);"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".StepInterruptionRepository;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionMaterializationSubpackageSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".StepInterruptionRepository;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        interruptionMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".PersistenceResult;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        completionMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".StepCompletionRepository;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        completionMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".PersistenceResult;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        completionMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence.*;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains("import io.paperagent.v2.persistence.*;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import static io.paperagent.v2.persistence"
                                + ".PersistenceOutcome.APPLIED;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "return io.paperagent.v2.persistence"
                                + ".PersistenceResult.applied(value);"));
        assertFalse(allowedPersistenceImports(sourceRoot, kernelSource)
                .contains("import io.paperagent.v2.persistence.InMemoryPersistence;"));
        assertFalse(allowedPersistenceImports(sourceRoot, kernelSource)
                .contains("import io.paperagent.v2.persistence.*;"));
        assertFalse(allowedPersistenceImports(sourceRoot, loopSource)
                .contains("import io.paperagent.v2.persistence.InMemoryPersistence;"));
        assertFalse(allowedPersistenceImports(sourceRoot, loopSource)
                .contains("import io.paperagent.v2.persistence.*;"));
        assertFalse(allowedPersistenceImports(sourceRoot, replanCompositionSource)
                .contains("import io.paperagent.v2.persistence.InMemoryPersistence;"));
        assertFalse(allowedPersistenceImports(sourceRoot, replanCompositionSource)
                .contains("import io.paperagent.v2.persistence.PlanReplanRepository;"));
        assertTrue(
                allowedPersistenceImports(sourceRoot, otherRuntimeSource)
                        .isEmpty());
    }

    @Test
    void executionDoesNotUseSuccessfulShortcut() throws Exception {
        Path module = moduleDirectory();
        for (Path sourceRoot : List.of(
                module.resolve("src/main/java"),
                module.resolve("src/test/java"))) {
            Path executionRoot = sourceRoot.resolve(
                    Path.of("io", "paperagent", "v2", "runtime", "execution"));
            if (!Files.isDirectory(executionRoot)) {
                continue;
            }
            try (var paths = Files.walk(executionRoot)) {
                for (Path sourcePath : paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList()) {
                    String source = Files.readString(sourcePath);
                    assertFalse(
                            source.contains(".successful("),
                            () -> sourcePath
                                    + " must classify persistence outcomes explicitly");
                }
            }
        }
    }

    private static boolean isBootstrapSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(
                Path.of("io", "paperagent", "v2", "runtime", "bootstrap"));
    }

    private static boolean isExecutionSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(
                Path.of("io", "paperagent", "v2", "runtime", "execution"));
    }

    private static boolean isExecutionStartSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "start"));
    }

    private static boolean isRecoveryMaterializationSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "materialization"));
    }

    private static boolean isRecoveryCompositionSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "composition"))
                && !isStepRecoveryCompositionSource(sourceRoot, sourcePath);
    }

    private static boolean isStepRecoveryCompositionSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        Path parent = relative.getParent();
        if (parent == null || !parent.equals(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "composition"))) {
            return false;
        }
        return Set.of(
                "DefaultStepRecoverer.java",
                "StepRecoverer.java",
                "StepRecoveryLeaseAttempt.java",
                "StepRecoveryRequest.java",
                "StepRecoveryCompositionOutcome.java",
                "RecoveredActiveStep.java",
                "StepRecoveryLeaseRejected.java",
                "StepRecoveryPersistenceRejected.java",
                "StepRecoveryLeaseDisposition.java",
                "StepRecoveryStage.java",
                "StepRecoveryProtocolCode.java",
                "StepRecoveryProtocolException.java",
                "StepRecoveryValidationCode.java",
                "StepRecoveryValidationException.java",
                "StepRecoveryCompositionValues.java")
                .contains(sourcePath.getFileName().toString());
    }

    private static boolean isContextCompositionSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "context",
                "composition"));
    }

    private static boolean isActivationMaterializationSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        Path parent = relative.getParent();
        return parent != null
                && parent.equals(activationMaterializationPackage());
    }

    private static boolean isActivationMaterializationTreeSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(activationMaterializationPackage());
    }

    private static boolean isActivationCompositionSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "activation",
                "composition"));
    }

    private static boolean isInterruptionMaterializationSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "interruption",
                "materialization"));
    }

    private static boolean isInterruptionCompositionSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "interruption",
                "composition"));
    }

    private static boolean isCompletionMaterializationSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "completion",
                "materialization"));
    }

    private static boolean isCompletionTreeSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "completion"));
    }

    private static boolean isKernelSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "kernel"));
    }

    private static boolean isLoopSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "loop"));
    }

    private static boolean isReplanCompositionSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "replan",
                "composition"));
    }

    private static Path activationMaterializationPackage() {
        return Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "activation",
                "materialization");
    }

    private static Set<String> allowedPersistenceImports(
            Path sourceRoot,
            Path sourcePath) {
        if (isReplanCompositionSource(sourceRoot, sourcePath)) {
            return ALLOWED_REPLAN_COMPOSITION_PERSISTENCE_IMPORTS;
        }
        if (isLoopSource(sourceRoot, sourcePath)) {
            return ALLOWED_LOOP_PERSISTENCE_IMPORTS;
        }
        if (isKernelSource(sourceRoot, sourcePath)) {
            return ALLOWED_KERNEL_PERSISTENCE_IMPORTS;
        }
        if (isStepRecoveryCompositionSource(sourceRoot, sourcePath)) {
            return ALLOWED_STEP_RECOVERY_COMPOSITION_PERSISTENCE_IMPORTS;
        }
        if (isActivationCompositionSource(sourceRoot, sourcePath)) {
            return ALLOWED_ACTIVATION_COMPOSITION_PERSISTENCE_IMPORTS;
        }
        if (isInterruptionCompositionSource(sourceRoot, sourcePath)) {
            return ALLOWED_INTERRUPTION_COMPOSITION_PERSISTENCE_IMPORTS;
        }
        if (isInterruptionMaterializationSource(sourceRoot, sourcePath)) {
            return ALLOWED_INTERRUPTION_MATERIALIZATION_PERSISTENCE_IMPORTS;
        }
        if (isCompletionMaterializationSource(sourceRoot, sourcePath)) {
            return ALLOWED_COMPLETION_MATERIALIZATION_PERSISTENCE_IMPORTS;
        }
        if (isCompletionTreeSource(sourceRoot, sourcePath)) {
            return Set.of();
        }
        if (isActivationMaterializationSource(sourceRoot, sourcePath)) {
            return ALLOWED_ACTIVATION_MATERIALIZATION_PERSISTENCE_IMPORTS;
        }
        if (isActivationMaterializationTreeSource(sourceRoot, sourcePath)) {
            return Set.of();
        }
        if (isContextCompositionSource(sourceRoot, sourcePath)) {
            return ALLOWED_CONTEXT_COMPOSITION_PERSISTENCE_IMPORTS;
        }
        if (isBootstrapSource(sourceRoot, sourcePath)) {
            return ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS;
        }
        if (isRecoveryCompositionSource(sourceRoot, sourcePath)) {
            return ALLOWED_RECOVERY_COMPOSITION_PERSISTENCE_IMPORTS;
        }
        if (isRecoveryMaterializationSource(sourceRoot, sourcePath)) {
            return ALLOWED_RECOVERY_MATERIALIZATION_PERSISTENCE_IMPORTS;
        }
        if (isExecutionStartSource(sourceRoot, sourcePath)) {
            return ALLOWED_EXECUTION_START_PERSISTENCE_IMPORTS;
        }
        if (isExecutionSource(sourceRoot, sourcePath)) {
            return ALLOWED_EXECUTION_PERSISTENCE_IMPORTS;
        }
        return Set.of();
    }

    private static Set<String> allowedWorkspaceImports(
            Path sourceRoot,
            Path sourcePath) {
        return isContextCompositionSource(sourceRoot, sourcePath)
                ? ALLOWED_CONTEXT_COMPOSITION_WORKSPACE_IMPORTS
                : Set.of();
    }

    private static Path moduleDirectory() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(current.resolve("pom.xml"))
                && current.getFileName().toString().equals("agent-runtime")) {
            return current;
        }
        return current.resolve("agent-runtime");
    }

    private static String text(Element parent, String name) {
        var elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().trim();
    }
}
