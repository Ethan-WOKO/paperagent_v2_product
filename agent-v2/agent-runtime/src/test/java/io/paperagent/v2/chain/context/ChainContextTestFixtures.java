package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextBuildFailureRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureWriter;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainContextRevisionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class ChainContextTestFixtures {
    static final Instant CREATED_AT = Instant.parse("2026-08-07T01:02:03Z");

    private ChainContextTestFixtures() {
    }

    static ContextRevisionRecord building(String revisionId, ChainRole role) {
        return building(revisionId, role, null);
    }

    static ContextRevisionRecord building(
            String revisionId, ChainRole role, String parentContextRevisionId) {
        return new ContextRevisionRecord(
                revisionId, "task-1", parentContextRevisionId, role, ChainWorkState.PLANNING,
                "INITIAL_PLANNING", "instruction-1", null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null,
                "projectors-v1", "pagination-v1", "policy-v1",
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, CREATED_AT, null);
    }

    static CanonicalJson json(String value) {
        return new CanonicalJson(1, ChainContextDigests.sha256(value), value);
    }

    static SourceFixture source(String marker) {
        return new SourceFixture(marker);
    }

    static final class SourceFixture implements ChainContextSource {
        private final AtomicInteger calls = new AtomicInteger();
        private String marker;
        private ChainContextModule omit;
        private ChainContextModule insufficient;
        private ChainContextModule empty;

        private SourceFixture(String marker) {
            this.marker = marker;
        }

        SourceFixture marker(String marker) {
            this.marker = marker;
            return this;
        }

        SourceFixture omit(ChainContextModule module) {
            this.omit = module;
            return this;
        }

        SourceFixture insufficient(ChainContextModule module) {
            this.insufficient = module;
            return this;
        }

        SourceFixture empty(ChainContextModule module) {
            this.empty = module;
            return this;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public List<ChainContextSourceSnapshot> project(ChainContextProjectionRequest request) {
            calls.incrementAndGet();
            List<ChainContextSourceSnapshot> projections = new ArrayList<>();
            for (ChainContextModule module : ChainContextInputMatrix.orderedModules()) {
                if (module == omit) {
                    continue;
                }
                ChainContextModuleStatus status = module == empty
                        ? ChainContextModuleStatus.EMPTY : ChainContextModuleStatus.PRESENT;
                ChainContextVersionMatrix.VersionRequirement version =
                        ChainContextVersionMatrix.requirement(module);
                Map<String, ChainContextValue> sourceVersion = values(
                        version.sourceVersionFields(), marker + "-source", false);
                Map<String, ChainContextValue> readBoundary = values(
                        version.readBoundaryFields(), marker + "-cut", false);
                List<String> requiredFields = module == insufficient
                        ? List.of() : request.requiredFields(module);
                Map<String, ChainContextValue> projectionFields = status == ChainContextModuleStatus.EMPTY
                        ? Map.of() : values(requiredFields, marker + "-projection", true);
                projections.add(new ChainContextSourceSnapshot(
                        module,
                        status,
                        sourceVersion,
                        readBoundary,
                        "projection-v1",
                        "pagination-v1",
                        Map.of("pageSize", ChainContextValue.number(20)),
                        projectionFields,
                        status == ChainContextModuleStatus.EMPTY
                                ? version.emptyWatermark() : null));
            }
            return projections;
        }

        private Map<String, ChainContextValue> values(
                List<String> names, String value, boolean bindRef) {
            Map<String, ChainContextValue> result = new LinkedHashMap<>();
            for (int index = 0; index < names.size(); index++) {
                ChainContextValue fieldValue = bindRef && index == 0
                        ? ChainContextValue.referencedText(value, "authority." + marker)
                        : ChainContextValue.text(value);
                result.put(names.get(index), fieldValue);
            }
            return result;
        }
    }

    static final class MemoryContextStore
            implements ChainContextRepository, ChainContextRevisionWriter,
            ChainContextBuildFailureRepository,
            ChainContextBuildFailureWriter {
        private final Map<String, ContextRevisionRecord> revisions = new LinkedHashMap<>();
        private final Map<String, Map<Integer, ContextModuleRecord>> modules = new HashMap<>();
        private final Map<String, ContextBuildFailureRecord> buildFailures =
                new LinkedHashMap<>();
        private long nextAuthoritySequence;
        private int completes;
        private int blocks;
        private int successfulModuleAppends;
        private int failAfterModuleAppends = Integer.MAX_VALUE;

        @Override
        public AppendResult<ContextRevisionRecord> createContextRevision(
                ContextRevisionRecord revision) {
            ContextRevisionRecord existing = revisions.putIfAbsent(
                    revision.contextRevisionId(), revision);
            return new AppendResult<>(existing == null ? revision : existing, existing != null);
        }

        @Override
        public AppendResult<ContextModuleRecord> appendContextModule(ContextModuleRecord module) {
            Map<Integer, ContextModuleRecord> byOrdinal = modules.computeIfAbsent(
                    module.contextRevisionId(), ignored -> new LinkedHashMap<>());
            if (!byOrdinal.containsKey(module.moduleOrdinal())
                    && successfulModuleAppends >= failAfterModuleAppends) {
                failAfterModuleAppends = Integer.MAX_VALUE;
                throw new IllegalStateException("simulated module append interruption");
            }
            ContextModuleRecord existing = byOrdinal.putIfAbsent(module.moduleOrdinal(), module);
            if (existing != null && (!existing.module().equals(module.module())
                    || !existing.projection().equals(module.projection()))) {
                throw new IllegalStateException("context module replay conflict");
            }
            if (existing == null) {
                successfulModuleAppends++;
            }
            return new AppendResult<>(existing == null ? module : existing, existing != null);
        }

        @Override
        public ContextRevisionRecord completeContextRevision(ContextRevisionRecord completeRevision) {
            completes++;
            return terminal(completeRevision);
        }

        @Override
        public ContextRevisionRecord blockContextRevision(ContextRevisionRecord blockedRevision) {
            blocks++;
            return terminal(blockedRevision);
        }

        private ContextRevisionRecord terminal(ContextRevisionRecord requested) {
            ContextRevisionRecord current = revisions.get(requested.contextRevisionId());
            if (current == null) {
                throw new IllegalStateException("missing context revision");
            }
            if (current.status() != ChainContextRevisionStatus.BUILDING) {
                if (current.equals(requested)) {
                    return current;
                }
                throw new IllegalStateException("context terminal CAS conflict");
            }
            revisions.put(requested.contextRevisionId(), requested);
            return requested;
        }

        @Override
        public Optional<ContextRevisionRecord> findContextRevision(String contextRevisionId) {
            return Optional.ofNullable(revisions.get(contextRevisionId));
        }

        @Override
        public List<ContextRevisionRecord> findContextRevisions(String taskId) {
            return revisions.values().stream()
                    .filter(revision -> revision.taskId().equals(taskId))
                    .toList();
        }

        @Override
        public List<ContextModuleRecord> findContextModules(String contextRevisionId) {
            return modules.getOrDefault(contextRevisionId, Map.of()).values().stream()
                    .sorted(Comparator.comparingInt(ContextModuleRecord::moduleOrdinal))
                    .toList();
        }

        @Override
        public Optional<ContextBuildFailureRecord> findContextBuildFailure(
                String contextRevisionId) {
            return Optional.ofNullable(buildFailures.get(contextRevisionId));
        }

        @Override
        public AuthoritativeAppendResult<ContextBuildFailureRecord>
                appendContextBuildFailure(
                        AuthoritativeFact<ContextBuildFailureRecord> failure) {
            ContextBuildFailureRecord existing = buildFailures.putIfAbsent(
                    failure.fact().contextRevisionId(), failure.fact());
            ContextBuildFailureRecord stored = existing == null
                    ? failure.fact() : existing;
            if (existing != null && !sameBuildFailure(existing,
                    failure.fact())) {
                throw new IllegalStateException(
                        "ContextBuildFailure replay conflict");
            }
            long sequence = existing == null
                    ? ++nextAuthoritySequence : nextAuthoritySequence;
            AuthorityEventRequest request = failure.event();
            AuthorityEventRecord event = new AuthorityEventRecord(
                    request.eventId(), request.taskId(), sequence,
                    request.eventType(), request.transitionId(),
                    request.sourceIdentitySha256(), request.committedAt());
            return new AuthoritativeAppendResult<>(event, stored,
                    existing != null);
        }

        private static boolean sameBuildFailure(
                ContextBuildFailureRecord left,
                ContextBuildFailureRecord right) {
            return left.contextBuildFailureId().equals(
                    right.contextBuildFailureId())
                    && left.taskId().equals(right.taskId())
                    && left.eventId().equals(right.eventId())
                    && left.contextRevisionId().equals(
                    right.contextRevisionId())
                    && left.role() == right.role()
                    && left.workState() == right.workState()
                    && left.callReason().equals(right.callReason())
                    && left.instructionId().equals(right.instructionId())
                    && left.failedModule() == right.failedModule()
                    && left.errorCode().equals(right.errorCode())
                    && left.projectorSetVersion().equals(
                    right.projectorSetVersion())
                    && left.paginationVersion().equals(
                    right.paginationVersion())
                    && left.runtimePolicyVersion().equals(
                    right.runtimePolicyVersion());
        }

        int completes() {
            return completes;
        }

        int blocks() {
            return blocks;
        }

        void replaceRevision(ContextRevisionRecord revision) {
            revisions.put(revision.contextRevisionId(), revision);
        }

        void replaceModule(ContextModuleRecord module) {
            modules.get(module.contextRevisionId()).put(module.moduleOrdinal(), module);
        }

        void replaceCompleteRequestDigest(String contextRevisionId, String requestDigest) {
            ContextRevisionRecord revision = revisions.get(contextRevisionId);
            revisions.put(contextRevisionId, new ContextRevisionRecord(
                    revision.contextRevisionId(), revision.taskId(),
                    revision.parentContextRevisionId(), revision.role(), revision.workState(),
                    revision.callReason(), revision.instructionId(), revision.taskFrameId(),
                    revision.planId(), revision.planRevisionId(), revision.planRevisionNumber(),
                    revision.stepId(), revision.activationEventId(), revision.projectId(),
                    revision.projectVersion(), revision.workspaceId(),
                    revision.candidateArtifactId(), revision.candidateFingerprint(),
                    revision.validationId(), revision.validationRequestDigest(),
                    revision.validationReceiptDigest(), revision.projectorSetVersion(),
                    revision.paginationVersion(), revision.runtimePolicyVersion(), revision.status(),
                    revision.moduleCount(), revision.requestManifest(), requestDigest,
                    revision.completionToken(), revision.blockedErrorCode(), revision.inputDigest(),
                    revision.createdAt(), revision.completedAt()));
        }

        void failAfterModuleAppends(int count) {
            this.failAfterModuleAppends = count;
        }
    }
}
