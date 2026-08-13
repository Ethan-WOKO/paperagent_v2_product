package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextBuildFailureRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureWriter;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainContextRevisionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FormattedJson;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Freezes the one ordered request seen by all model roles. The persistence
 * writer owns the BUILDING-to-terminal CAS; this class owns the complete-set,
 * matrix, digest and historical-recovery gates immediately before that CAS.
 */
public final class DefaultChainContextManager implements ChainContextManager {
    public static final String INPUT_BLOCKED_ERROR_CODE = "CONTEXT_INPUT_BLOCKED";

    private final ChainContextRepository repository;
    private final ChainContextRevisionWriter writer;
    private final ChainContextBuildFailureRepository buildFailures;
    private final ChainContextBuildFailureWriter buildFailureWriter;
    private final ChainContextSource source;
    private final Clock clock;
    private final Supplier<String> completionTokenFactory;
    private final ChainContextManifestCodec manifests = new ChainContextManifestCodec();

    public DefaultChainContextManager(
            ChainContextRepository repository,
            ChainContextRevisionWriter writer,
            ChainContextSource source) {
        this(repository, writer, source, Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
    }

    public DefaultChainContextManager(
            ChainContextRepository repository,
            ChainContextRevisionWriter writer,
            ChainContextSource source,
            Clock clock,
            Supplier<String> completionTokenFactory) {
        this(repository, writer, buildFailureRepository(repository),
                buildFailureWriter(writer), source, clock,
                completionTokenFactory);
    }

    public DefaultChainContextManager(
            ChainContextRepository repository,
            ChainContextRevisionWriter writer,
            ChainContextBuildFailureRepository buildFailures,
            ChainContextBuildFailureWriter buildFailureWriter,
            ChainContextSource source,
            Clock clock,
            Supplier<String> completionTokenFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.buildFailures = Objects.requireNonNull(
                buildFailures, "buildFailures");
        this.buildFailureWriter = Objects.requireNonNull(
                buildFailureWriter, "buildFailureWriter");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.completionTokenFactory = Objects.requireNonNull(
                completionTokenFactory, "completionTokenFactory");
    }

    @Override
    public ChainContextFreezeOutcome freeze(ChainContextFreezeRequest request) {
        Objects.requireNonNull(request, "request");
        ContextRevisionRecord requested = request.buildingRevision();
        validateParent(requested);
        ContextRevisionRecord building = writer.createContextRevision(requested).value();
        if (!building.taskId().equals(requested.taskId())
                || !building.contextRevisionId().equals(requested.contextRevisionId())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_TASK_MISMATCH,
                    "persisted context creation returned a different task or revision identity");
        }
        if (building.status() != ChainContextRevisionStatus.BUILDING) {
            ChainFrozenContext recovered = recover(building.taskId(), building.contextRevisionId());
            return outcomeFor(recovered, requestCharacters(recovered.canonicalPrompt()));
        }
        ContextBuildFailureRecord existingFailure = buildFailures
                .findContextBuildFailure(building.contextRevisionId())
                .orElse(null);
        if (existingFailure != null) {
            verifyBuildFailure(building, existingFailure);
            return new ChainContextFreezeOutcome.BuildBlocked(
                    building, existingFailure);
        }

        ChainContextProjectionRequest projectionRequest = new ChainContextProjectionRequest(
                building, request.maxRequestCharacters());
        Map<Integer, ContextModuleRecord> existingModules = new java.util.HashMap<>();
        for (ContextModuleRecord existing : repository.findContextModules(
                building.contextRevisionId())) {
            if (existingModules.put(existing.moduleOrdinal(), existing) != null) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_DUPLICATE,
                        "BUILDING revision contains duplicate module ordinals");
            }
        }
        Map<ChainContextModule, ChainContextSourceSnapshot> projected;
        try {
            projected = validateSourceSet(
                    source.project(projectionRequest), projectionRequest);
        } catch (ChainContextException blocked) {
            if (blocked.code() != ChainContextErrorCode.CONTEXT_INPUT_BLOCKED
                    || blocked.failedModule() == null
                    || blocked.failureDisposition()
                    != ChainContextException.FailureDisposition
                    .FORMAL_BUILD_BLOCK) {
                throw blocked;
            }
            ContextBuildFailureRecord failure = appendBuildFailure(
                    building, blocked.failedModule());
            return new ChainContextFreezeOutcome.BuildBlocked(
                    building, failure);
        }

        Instant moduleCreatedAt = clock.instant();
        List<ContextModuleRecord> persisted = new ArrayList<>(13);
        for (ChainContextModule module : ChainContextInputMatrix.orderedModules()) {
            ChainContextSourceSnapshot snapshot = projected.get(module);
            ContextModuleRecord moduleRecord = new ContextModuleRecord(
                    building.contextRevisionId(),
                    building.taskId(),
                    module.ordinalCode(),
                    module,
                    snapshot.presenceKind(),
                    snapshot.sourceVersion(),
                    snapshot.readBoundary(),
                    snapshot.projectionVersion(),
                    snapshot.paginationVersion(),
                    snapshot.projectionParameters(),
                    snapshot.projection(),
                    moduleCreatedAt);
            ContextModuleRecord existing = existingModules.get(module.ordinalCode());
            if (existing != null) {
                if (!sameFrozenModule(existing, moduleRecord)) {
                    throw new ChainContextException(
                            ChainContextErrorCode.CONTEXT_MODULE_REPLAY_MISMATCH,
                            "BUILDING replay changed the frozen module " + module);
                }
                persisted.add(existing);
            } else {
                ContextModuleRecord appended = writer.appendContextModule(moduleRecord).value();
                if (!sameFrozenModule(appended, moduleRecord)) {
                    throw new ChainContextException(
                            ChainContextErrorCode.CONTEXT_MODULE_REPLAY_MISMATCH,
                            "module append returned different frozen contents for " + module);
                }
                persisted.add(appended);
            }
        }
        persisted = persisted.stream()
                .sorted(Comparator.comparingInt(ContextModuleRecord::moduleOrdinal))
                .toList();
        validatePersistedModules(building, persisted);

        FormattedJson manifest = manifests.manifest(persisted);
        String canonicalPrompt = manifests.canonicalPrompt(persisted);
        String promptDigest = ChainContextDigests.sha256(canonicalPrompt);
        int inputCharacters = requestCharacters(canonicalPrompt);
        Instant completedAt = clock.instant();
        ContextRevisionRecord terminal;
        if (inputCharacters > request.maxRequestCharacters()) {
            terminal = terminalRevision(
                    building,
                    ChainContextRevisionStatus.INPUT_BLOCKED,
                    manifest,
                    null,
                    null,
                    INPUT_BLOCKED_ERROR_CODE,
                    promptDigest,
                    completedAt);
            terminal = writer.blockContextRevision(terminal);
        } else {
            String completionToken = completionTokenFactory.get();
            if (completionToken == null || completionToken.isBlank()) {
                throw new IllegalStateException("completionTokenFactory returned a blank token");
            }
            terminal = terminalRevision(
                    building,
                    ChainContextRevisionStatus.COMPLETE,
                    manifest,
                    promptDigest,
                    completionToken,
                    null,
                    null,
                    completedAt);
            terminal = writer.completeContextRevision(terminal);
        }
        ChainFrozenContext frozen = validateRecovered(terminal, persisted);
        return outcomeFor(frozen, inputCharacters);
    }

    private ContextBuildFailureRecord appendBuildFailure(
            ContextRevisionRecord building,
            ChainContextModule failedModule) {
        String frozenIdentity = building.contextRevisionId() + "\0"
                + building.taskId() + "\0" + building.role().name() + "\0"
                + building.workState().name() + "\0" + building.callReason()
                + "\0" + building.instructionId() + "\0"
                + failedModule.wireName() + "\0" + INPUT_BLOCKED_ERROR_CODE
                + "\0" + building.projectorSetVersion() + "\0"
                + building.paginationVersion() + "\0"
                + building.runtimePolicyVersion();
        String identity = ChainContextDigests.sha256(frozenIdentity);
        String failureId = "context-build-failure." + identity;
        String eventId = "context-build-failure.event." + identity;
        Instant occurredAt = clock.instant();
        ContextBuildFailureRecord requested = new ContextBuildFailureRecord(
                failureId, building.taskId(), eventId,
                building.contextRevisionId(), building.role(),
                building.workState(), building.callReason(),
                building.instructionId(), failedModule,
                INPUT_BLOCKED_ERROR_CODE, building.projectorSetVersion(),
                building.paginationVersion(), building.runtimePolicyVersion(),
                occurredAt);
        var event = new io.paperagent.v2.chain.ChainPersistenceRecords
                .AuthorityEventRequest(eventId, building.taskId(),
                "CONTEXT_BUILD_FAILURE", null,
                ChainContextDigests.sha256(frozenIdentity + "\0" + failureId),
                occurredAt);
        var appended = buildFailureWriter.appendContextBuildFailure(
                new io.paperagent.v2.chain.ChainPersistenceRecords
                        .AuthoritativeFact<>(event, requested));
        verifyBuildFailure(building, appended.fact());
        if (!eventId.equals(appended.event().eventId())
                || !building.taskId().equals(appended.event().taskId())
                || !"CONTEXT_BUILD_FAILURE".equals(
                appended.event().eventType())
                || appended.event().transitionId() != null
                || !event.sourceIdentitySha256().equals(
                appended.event().sourceIdentitySha256())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REPLAY_MISMATCH,
                    "ContextBuildFailure authority event changed identity");
        }
        return appended.fact();
    }

    private static void verifyBuildFailure(
            ContextRevisionRecord building,
            ContextBuildFailureRecord failure) {
        if (building.status() != ChainContextRevisionStatus.BUILDING
                || !failure.contextRevisionId().equals(
                building.contextRevisionId())
                || !failure.taskId().equals(building.taskId())
                || failure.role() != building.role()
                || failure.workState() != building.workState()
                || !failure.callReason().equals(building.callReason())
                || !failure.instructionId().equals(building.instructionId())
                || !INPUT_BLOCKED_ERROR_CODE.equals(failure.errorCode())
                || !failure.projectorSetVersion().equals(
                building.projectorSetVersion())
                || !failure.paginationVersion().equals(
                building.paginationVersion())
                || !failure.runtimePolicyVersion().equals(
                building.runtimePolicyVersion())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REPLAY_MISMATCH,
                    "ContextBuildFailure changed the BUILDING revision identity");
        }
    }

    private static ChainContextBuildFailureRepository buildFailureRepository(
            ChainContextRepository repository) {
        if (repository instanceof ChainContextBuildFailureRepository failures) {
            return failures;
        }
        throw new IllegalArgumentException(
                "Context repository must support ContextBuildFailure recovery");
    }

    private static ChainContextBuildFailureWriter buildFailureWriter(
            ChainContextRevisionWriter writer) {
        if (writer instanceof ChainContextBuildFailureWriter failures) {
            return failures;
        }
        throw new IllegalArgumentException(
                "Context writer must support ContextBuildFailure authority");
    }

    @Override
    public ChainFrozenContext recover(String taskId, String contextRevisionId) {
        required(taskId, "taskId");
        required(contextRevisionId, "contextRevisionId");
        ContextRevisionRecord revision = repository.findContextRevision(contextRevisionId)
                .orElseThrow(() -> new ChainContextException(
                        ChainContextErrorCode.CONTEXT_REVISION_NOT_FOUND,
                        "context revision does not exist: " + contextRevisionId));
        if (!taskId.equals(revision.taskId())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_TASK_MISMATCH,
                    "context revision does not belong to the requested task");
        }
        if (revision.status() == ChainContextRevisionStatus.BUILDING) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_NOT_RECOVERABLE,
                    "a BUILDING context revision cannot be used as model input");
        }
        return validateRecovered(revision, repository.findContextModules(contextRevisionId));
    }

    private Map<ChainContextModule, ChainContextSourceSnapshot> validateSourceSet(
            List<ChainContextSourceSnapshot> snapshots,
            ChainContextProjectionRequest request) {
        if (snapshots == null || snapshots.size() != 13) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_SOURCE_MODULE_SET_INVALID,
                    "context source must return exactly thirteen modules");
        }
        EnumMap<ChainContextModule, ChainContextSourceSnapshot> byModule =
                new EnumMap<>(ChainContextModule.class);
        for (ChainContextSourceSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_SET_INVALID,
                        "context source returned a null module");
            }
            if (byModule.put(snapshot.module(), snapshot) != null) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_DUPLICATE,
                        "duplicate context module: " + snapshot.module());
            }
        }
        for (ChainContextModule module : ChainContextInputMatrix.orderedModules()) {
            ChainContextSourceSnapshot snapshot = byModule.get(module);
            if (snapshot == null) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_MISSING,
                        "missing context module: " + module);
            }
            validateProjection(request, snapshot);
        }
        return Map.copyOf(byModule);
    }

    private void validateParent(ContextRevisionRecord requested) {
        if (requested.parentContextRevisionId() == null) {
            return;
        }
        ContextRevisionRecord parent = repository.findContextRevision(
                        requested.parentContextRevisionId())
                .orElseThrow(() -> new ChainContextException(
                        ChainContextErrorCode.CONTEXT_PARENT_REVISION_NOT_FOUND,
                        "parent context revision does not exist"));
        if (!parent.taskId().equals(requested.taskId())
                || parent.status() == ChainContextRevisionStatus.BUILDING) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_PARENT_REVISION_INVALID,
                    "parent context revision must be a frozen revision of the same task");
        }
    }

    private void validateProjection(
            ChainContextProjectionRequest request,
            ChainContextSourceSnapshot snapshot) {
        if (requiresPresent(request.buildingRevision(), snapshot.module())
                && snapshot.presenceKind() == ChainContextModuleStatus.EMPTY) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REQUIRED_MODULE_EMPTY,
                    "required context module is EMPTY: " + snapshot.module());
        }
        if (snapshot.presenceKind() == ChainContextModuleStatus.PRESENT) {
            Set<String> missing = new HashSet<>(request.requiredFields(snapshot.module()));
            missing.removeAll(snapshot.projectionFieldNames());
            if (!missing.isEmpty()) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                        "context module " + snapshot.module()
                                + " is missing required projection fields: " + missing);
            }
        }
    }

    private boolean requiresPresent(ContextRevisionRecord revision, ChainContextModule module) {
        if (!ChainContextVersionMatrix.requirement(module).emptyAllowed()) {
            return true;
        }
        if (module == ChainContextModule.USER_INSTRUCTION_CHAIN
                || module == ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS) {
            return true;
        }
        if (module == ChainContextModule.TASK_CONTRACT && revision.taskFrameId() != null) {
            return true;
        }
        if (module == ChainContextModule.PLAN_AND_STEP_CONTRACT && revision.planId() != null) {
            return true;
        }
        if (module == ChainContextModule.PROJECT_AND_INPUT_MATERIALS && revision.projectId() != null) {
            return true;
        }
        if (module == ChainContextModule.WORKSPACE_AND_CANDIDATE
                && revision.candidateArtifactId() != null) {
            return true;
        }
        return module == ChainContextModule.VALIDATION_AND_PUBLISH
                && revision.validationId() != null;
    }

    private ChainFrozenContext validateRecovered(
            ContextRevisionRecord revision,
            List<ContextModuleRecord> records) {
        List<ContextModuleRecord> modules = List.copyOf(records).stream()
                .sorted(Comparator.comparingInt(ContextModuleRecord::moduleOrdinal))
                .toList();
        validatePersistedModules(revision, modules);
        FormattedJson manifest = manifests.manifest(modules);
        if (!manifest.equals(revision.requestManifest())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_MANIFEST_MISMATCH,
                    "stored context manifest does not match the frozen modules");
        }
        String canonicalPrompt = manifests.canonicalPrompt(modules);
        String digest = ChainContextDigests.sha256(canonicalPrompt);
        if (revision.status() == ChainContextRevisionStatus.COMPLETE
                && !digest.equals(revision.requestDigest())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_MANIFEST_MISMATCH,
                    "complete request digest does not match its canonical prompt");
        }
        if (revision.status() == ChainContextRevisionStatus.INPUT_BLOCKED
                && !digest.equals(revision.inputDigest())) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_MANIFEST_MISMATCH,
                    "blocked input digest does not match its canonical prompt");
        }
        return new ChainFrozenContext(
                revision, modules, canonicalPrompt, visibleSourceRefs(modules));
    }

    private void validatePersistedModules(
            ContextRevisionRecord revision,
            List<ContextModuleRecord> modules) {
        manifests.verifyCompleteSet(modules);
        for (ContextModuleRecord module : modules) {
            if (!revision.contextRevisionId().equals(module.contextRevisionId())
                    || !revision.taskId().equals(module.taskId())) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_REVISION_TASK_MISMATCH,
                        "context module task or revision identity mismatch");
            }
            ChainContextDigests.verify(module.sourceVersion().json(),
                    module.sourceVersion().sha256(), "sourceVersion");
            ChainContextDigests.verify(module.readBoundary().json(),
                    module.readBoundary().sha256(), "readBoundary");
            ChainContextDigests.verify(module.projectionParameters().json(),
                    module.projectionParameters().sha256(), "projectionParameters");
            ChainContextDigests.verify(module.projection().json(),
                    module.projection().sha256(), "projection");
            if (module.presenceKind() == ChainContextModuleStatus.EMPTY
                    && !ChainContextVersionMatrix.requirement(module.module()).emptyAllowed()) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_REQUIRED_MODULE_EMPTY,
                        "stored required module is EMPTY");
            }
            validateFrozenModuleShape(revision, module);
        }
    }

    private ChainContextFreezeOutcome outcomeFor(
            ChainFrozenContext frozen, int inputCharacters) {
        if (frozen.revision().status() == ChainContextRevisionStatus.COMPLETE) {
            return new ChainContextFreezeOutcome.Complete(frozen);
        }
        return new ChainContextFreezeOutcome.InputBlocked(
                frozen,
                frozen.revision().blockedErrorCode(),
                inputCharacters);
    }

    private static int requestCharacters(String canonicalPrompt) {
        long total = canonicalPrompt.codePointCount(0, canonicalPrompt.length());
        if (total > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    private static boolean sameFrozenModule(
            ContextModuleRecord left, ContextModuleRecord right) {
        return left.contextRevisionId().equals(right.contextRevisionId())
                && left.taskId().equals(right.taskId())
                && left.moduleOrdinal() == right.moduleOrdinal()
                && left.module() == right.module()
                && left.presenceKind() == right.presenceKind()
                && left.sourceVersion().equals(right.sourceVersion())
                && left.readBoundary().equals(right.readBoundary())
                && left.projectionVersion().equals(right.projectionVersion())
                && left.paginationVersion().equals(right.paginationVersion())
                && left.projectionParameters().equals(right.projectionParameters())
                && left.projection().equals(right.projection());
    }

    private static void validateFrozenModuleShape(
            ContextRevisionRecord revision, ContextModuleRecord module) {
        ChainContextVersionMatrix.VersionRequirement version =
                ChainContextVersionMatrix.requirement(module.module());
        Map<String, Object> sourceVersion = ChainContextCanonicalJson.parseObject(
                module.sourceVersion().json());
        Map<String, Object> readBoundary = ChainContextCanonicalJson.parseObject(
                module.readBoundary().json());
        ChainContextCanonicalJson.parseObject(module.projectionParameters().json());
        if (!sourceVersion.keySet().equals(Set.copyOf(version.sourceVersionFields()))
                || !readBoundary.keySet().equals(Set.copyOf(version.readBoundaryFields()))) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    "stored source version or read boundary does not match the frozen component vector");
        }
        Map<String, Object> projection = ChainContextCanonicalJson.parseObject(
                module.projection().json());
        Object fieldsValue = projection.get("fields");
        Object refsValue = projection.get("visibleSourceRefs");
        if (!(fieldsValue instanceof Map<?, ?>) || !(refsValue instanceof List<?>)) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    "stored projection lacks canonical fields or visible refs");
        }
        Set<String> expectedEnvelopeKeys = module.presenceKind() == ChainContextModuleStatus.EMPTY
                ? Set.of("emptyWatermark", "fields", "module", "status", "visibleSourceRefs")
                : Set.of("fields", "module", "status", "visibleSourceRefs");
        if (!projection.keySet().equals(expectedEnvelopeKeys)) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    "stored projection envelope contains missing or unknown keys");
        }
        if (!module.module().wireName().equals(projection.get("module"))
                || !module.presenceKind().name().equals(projection.get("status"))) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    "stored projection module/status identity mismatch");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) fieldsValue;
        if (module.presenceKind() == ChainContextModuleStatus.EMPTY) {
            if (!fields.isEmpty()
                    || !version.emptyWatermark().equals(projection.get("emptyWatermark"))) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_EMPTY_PROJECTION_INVALID,
                        "stored EMPTY projection has the wrong fields or watermark");
            }
        } else {
            if (projection.containsKey("emptyWatermark")) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_EMPTY_PROJECTION_INVALID,
                        "stored PRESENT projection carries an EMPTY watermark");
            }
            Set<String> missing = new HashSet<>(ChainContextInputMatrix.requiredProjectionFields(
                    revision.role(), module.module()));
            missing.removeAll(fields.keySet());
            if (!missing.isEmpty()) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                        "stored projection is missing role fields: " + missing);
            }
        }
        List<String> refs = new ArrayList<>();
        for (Object ref : (List<?>) refsValue) {
            if (!(ref instanceof String text) || text.isBlank()) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                        "stored projection contains an invalid visible source ref");
            }
            refs.add(text);
        }
        List<String> sortedUniqueRefs = refs.stream().distinct().sorted().toList();
        if (!refs.equals(sortedUniqueRefs)) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    "stored visible source refs must be sorted and unique");
        }
    }

    private static Set<String> visibleSourceRefs(List<ContextModuleRecord> modules) {
        Set<String> refs = new java.util.TreeSet<>();
        for (ContextModuleRecord module : modules) {
            Object value = ChainContextCanonicalJson.parseObject(
                    module.projection().json()).get("visibleSourceRefs");
            if (!(value instanceof List<?> values)) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                        "stored projection lacks visible source refs");
            }
            for (Object ref : values) {
                if (!(ref instanceof String text) || text.isBlank()) {
                    throw new ChainContextException(
                            ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                            "stored projection contains an invalid visible source ref");
                }
                refs.add((String) ref);
            }
        }
        return java.util.Collections.unmodifiableSet(refs);
    }

    private static ContextRevisionRecord terminalRevision(
            ContextRevisionRecord building,
            ChainContextRevisionStatus status,
            FormattedJson manifest,
            String requestDigest,
            String completionToken,
            String blockedErrorCode,
            String inputDigest,
            Instant completedAt) {
        return new ContextRevisionRecord(
                building.contextRevisionId(), building.taskId(), building.parentContextRevisionId(),
                building.role(), building.workState(), building.callReason(), building.instructionId(),
                building.taskFrameId(), building.planId(), building.planRevisionId(),
                building.planRevisionNumber(), building.stepId(), building.activationEventId(),
                building.projectId(), building.projectVersion(), building.workspaceId(),
                building.candidateArtifactId(), building.candidateFingerprint(), building.validationId(),
                building.validationRequestDigest(), building.validationReceiptDigest(),
                building.projectorSetVersion(), building.paginationVersion(),
                building.runtimePolicyVersion(), status, 13, manifest, requestDigest,
                completionToken, blockedErrorCode, inputDigest, building.createdAt(), completedAt);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
