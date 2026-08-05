package com.yanban.api.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Explicit-user-only immutable Project revision workflow. It is intentionally not an Agent tool. */
@Service
public class ProjectRevisionWorkflowService {
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() { };

    private final ProjectRepository projects;
    private final ProjectRevisionRepository revisions;
    private final ProjectRevisionOperationRepository operations;
    private final CandidateChangeArtifactService candidates;
    private final ProjectObjectStorage storage;
    private final ProjectStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final CandidateValidationApplicationGate validationGate;

    public ProjectRevisionWorkflowService(ProjectRepository projects, ProjectRevisionRepository revisions,
                                          ProjectRevisionOperationRepository operations,
                                          CandidateChangeArtifactService candidates, ProjectObjectStorage storage,
                                          ProjectStorageProperties properties, ObjectMapper objectMapper,
                                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                                          CandidateValidationApplicationGate validationGate) {
        this.projects = projects;
        this.revisions = revisions;
        this.operations = operations;
        this.candidates = candidates;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.validationGate = validationGate;
    }

    public ProjectRevisionOperationResponse applyCandidate(Long userId, Long projectId, Long artifactId,
                                                           String idempotencyKey, String ifMatch,
                                                           ApplyCandidateRequest request) {
        requireIdentity(userId, projectId);
        String key = requireIdempotencyKey(idempotencyKey);
        List<Integer> accepted = sortedDistinctNonEmpty(request == null ? null : request.acceptedChangeIndexes());
        String expectedVersion = requireIfMatch(ifMatch);
        String validationId = request == null ? null : request.validationId();
        String requestHash = requestHash("APPLICATION:" + validationId, artifactId, expectedVersion, accepted);
        ProjectRevisionOperation existing = findReplay(userId, projectId, key, requestHash);
        if (existing != null) return replay(existing);

        CandidateArtifactResponse candidate = candidates.getCurrent(userId, artifactId);
        requireApplicableCandidate(userId, projectId, expectedVersion, candidate,
                accepted);
        validationGate.requireSuccessful(userId, projectId, artifactId, validationId,
                expectedVersion, candidate, accepted);

        return applyValidatedCandidate(userId, projectId, artifactId, key,
                requestHash, expectedVersion, candidate, accepted,
                validationId);
    }

    /** Server-only path for a Candidate bound to an exact successful V2 sandbox Receipt. */
    public ProjectRevisionOperationResponse applyAutomatically(
            Long userId, Long projectId, Long artifactId,
            String idempotencyKey, String expectedVersion,
            String expectedCandidateFingerprint, String receiptId) {
        requireIdentity(userId, projectId);
        String key = requireIdempotencyKey(idempotencyKey);
        if (expectedVersion == null
                || !expectedVersion.matches("[0-9a-f]{64}")
                || expectedCandidateFingerprint == null
                || !expectedCandidateFingerprint.matches("[0-9a-f]{64}")
                || receiptId == null || receiptId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Automatic Candidate application proof is invalid");
        }
        CandidateArtifactResponse candidate = candidates.getCurrent(
                userId, artifactId);
        List<Integer> accepted = java.util.stream.IntStream.range(
                        0, candidate.changes().size())
                .boxed().toList();
        String requestHash = requestHash(
                "AUTOMATIC_APPLICATION:" + receiptId, artifactId,
                expectedVersion, accepted);
        ProjectRevisionOperation existing = findReplay(
                userId, projectId, key, requestHash);
        if (existing != null) {
            return replay(existing);
        }
        requireApplicableCandidate(userId, projectId, expectedVersion, candidate,
                accepted);
        if (!expectedCandidateFingerprint.equals(
                candidate.fingerprint().sha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Automatic Candidate fingerprint changed");
        }
        return applyValidatedCandidate(userId, projectId, artifactId, key,
                requestHash, expectedVersion, candidate, accepted, null);
    }

    private ProjectRevisionOperationResponse applyValidatedCandidate(
            Long userId, Long projectId, Long artifactId, String key,
            String requestHash, String expectedVersion,
            CandidateArtifactResponse candidate, List<Integer> accepted,
            String validationId) {
        List<Integer> rejected = complement(
                candidate.changes().size(), accepted);

        ReservedOperation reserved = reserve(userId, projectId, key, requestHash,
                ProjectRevisionOperation.Type.APPLICATION, expectedVersion, artifactId,
                candidate.fingerprint().sha256(), accepted, rejected);
        if (reserved.replay() != null) return replay(reserved.replay());

        String newPrefix = storage.createPrefix(userId);
        try {
            TrustedManifest finalManifest = createRevisionSnapshot(reserved.basePrefix(), newPrefix,
                    candidate.changes(), new HashSet<>(accepted));
            return publishApplication(reserved, newPrefix, finalManifest, validationId);
        } catch (RuntimeException ex) {
            fail(reserved.operationId(), errorCode(ex));
            throw ex;
        }
    }

    private void requireApplicableCandidate(
            Long userId, Long projectId, String expectedVersion,
            CandidateArtifactResponse candidate, List<Integer> accepted) {
        requireManagedProject(userId, projectId);
        requireAcceptedIndexes(candidate.changes().size(), accepted);
        for (Integer index : accepted) {
            if (ProjectAssetAdmissionPolicy.readOnlyBinaryPath(
                    candidate.changes().get(index)
                            .relativePath().value())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Binary Project assets are read-only and cannot be applied as Candidate changes");
            }
        }
        if (candidate.projectId() != projectId
                || !candidate.projectVersion().value()
                        .equals(expectedVersion)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Candidate does not match the current Project version");
        }
        if (candidate.governanceStatus()
                        != CandidateChangeSet.GovernanceStatus.VALIDATED
                || !candidate.validation().valid()
                || candidate.applicationStatus()
                        != CandidateChangeSet.ApplicationStatus.NOT_APPLIED) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Candidate is stale or invalid and cannot be applied");
        }
    }

    public List<ProjectRevisionResponse> listRevisions(Long userId, Long projectId) {
        requireIdentity(userId, projectId);
        Long currentRevisionId = transactions.execute(status -> {
            Project project = lockedManagedProject(userId, projectId);
            TrustedManifest manifest = trustedManifest(project.getRootPath());
            if (project.getCurrentRevisionId() == null) {
                verifyStoredFiles(project.getRootPath(), manifest.entries());
            }
            return ensureCurrentRevision(project, manifest).getId();
        });
        return revisions.findByProjectIdAndUserIdOrderByCreatedAtDescIdDesc(projectId, userId).stream()
                .map(revision -> ProjectRevisionResponse.from(revision, currentRevisionId)).toList();
    }

    public ProjectRevisionOperationResponse rollback(Long userId, Long projectId, Long revisionId,
                                                     String idempotencyKey, String ifMatch) {
        requireIdentity(userId, projectId);
        String key = requireIdempotencyKey(idempotencyKey);
        String expectedVersion = requireIfMatch(ifMatch);
        String requestHash = requestHash("ROLLBACK", revisionId, expectedVersion, List.of());
        ProjectRevisionOperation existing = findReplay(userId, projectId, key, requestHash);
        if (existing != null) return replay(existing);
        ProjectRevision target = revisions.findByIdAndProjectIdAndUserId(revisionId, projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project revision not found"));
        requireManagedProject(userId, projectId);
        requireCompleteRevision(target);

        ReservedOperation reserved = reserve(userId, projectId, key, requestHash,
                ProjectRevisionOperation.Type.ROLLBACK, expectedVersion, null, null, List.of(), List.of());
        if (reserved.replay() != null) return replay(reserved.replay());
        try {
            return transactions.execute(status -> {
                Project project = lockedManagedProject(userId, projectId);
                TrustedManifest current = trustedManifest(project.getRootPath());
                requireCurrent(project, reserved, current);
                ProjectRevision trustedTarget = revisions.findByIdAndProjectIdAndUserId(revisionId, projectId, userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project revision not found"));
                requireCompleteRevision(trustedTarget);
                project.publishRevision(trustedTarget);
                projects.saveAndFlush(project);
                ProjectRevisionOperation operation = operations.findById(reserved.operationId()).orElseThrow();
                operation.succeed(trustedTarget.getId(), trustedTarget.getProjectVersion());
                operations.saveAndFlush(operation);
                return response(operation);
            });
        } catch (RuntimeException ex) {
            fail(reserved.operationId(), errorCode(ex));
            throw ex;
        }
    }

    public String exportFilename(Long userId, Long projectId, Long revisionId) {
        Project project = requireManagedProject(userId, projectId);
        requireRevision(userId, projectId, revisionId);
        String safe = project.getName().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (safe.isBlank()) safe = "project";
        return safe + "-revision-" + revisionId + ".zip";
    }

    public void exportRevision(Long userId, Long projectId, Long revisionId, OutputStream output) {
        requireManagedProject(userId, projectId);
        ProjectRevision revision = requireRevision(userId, projectId, revisionId);
        TrustedManifest manifest = requireCompleteRevision(revision);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (ProjectObjectEntry entry : manifest.entries()) {
                byte[] content = storage.readFile(revision.getObjectPrefix(), entry.path(), properties.getMaxFileBytes());
                ZipEntry zipEntry = new ZipEntry(new ProjectRelativePath(entry.path()).value());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Project revision export failed", ex);
        }
    }

    private ReservedOperation reserve(Long userId, Long projectId, String key, String requestHash,
                                      ProjectRevisionOperation.Type type, String expectedVersion,
                                      Long artifactId, String fingerprint, List<Integer> accepted,
                                      List<Integer> rejected) {
        try {
            return transactions.execute(status -> {
                ProjectRevisionOperation replay = operations
                        .findByUserIdAndProjectIdAndIdempotencyKey(userId, projectId, key).orElse(null);
                if (replay != null) {
                    requireSamePayload(replay, requestHash);
                    return new ReservedOperation(null, null, null, replay);
                }
                Project project = lockedManagedProject(userId, projectId);
                TrustedManifest manifest = trustedManifest(project.getRootPath());
                if (!expectedVersion.equals(manifest.version())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Project current version changed");
                }
                ProjectRevision base = ensureCurrentRevision(project, manifest);
                ProjectRevisionOperation operation = new ProjectRevisionOperation(projectId, userId, type, key,
                        requestHash, base.getId(), manifest.version(), artifactId, fingerprint,
                        json(accepted), json(rejected));
                operation = operations.saveAndFlush(operation);
                return new ReservedOperation(operation.getId(), base.getId(), project.getRootPath(), null);
            });
        } catch (DataIntegrityViolationException ex) {
            ProjectRevisionOperation replay = operations
                    .findByUserIdAndProjectIdAndIdempotencyKey(userId, projectId, key).orElseThrow(() -> ex);
            requireSamePayload(replay, requestHash);
            return new ReservedOperation(null, null, null, replay);
        }
    }

    private ProjectRevisionOperationResponse publishApplication(ReservedOperation reserved, String prefix,
                                                                TrustedManifest manifest, String validationId) {
        return transactions.execute(status -> {
            ProjectRevisionOperation operation = operations.findById(reserved.operationId()).orElseThrow();
            Project project = lockedManagedProject(operation.getUserId(), operation.getProjectId());
            TrustedManifest current = trustedManifest(project.getRootPath());
            requireCurrent(project, reserved, current);
            ProjectRevision revision = revisions.saveAndFlush(new ProjectRevision(project.getId(), project.getUserId(),
                    manifest.version(), prefix, manifest.entries().size(), manifest.totalBytes(),
                    ProjectRevision.SourceType.APPLICATION, operation.getId()));
            project.publishRevision(revision);
            projects.saveAndFlush(project);
            operation.succeed(revision.getId(), revision.getProjectVersion());
            operations.saveAndFlush(operation);
            if (validationId != null) {
                validationGate.markApplied(
                        validationId, operation.getId(), revision.getId());
            }
            return response(operation);
        });
    }

    private TrustedManifest createRevisionSnapshot(String basePrefix, String resultPrefix,
                                                   List<CandidateFileChange> changes, Set<Integer> accepted) {
        TrustedManifest base = trustedManifest(basePrefix);
        Map<String, CandidateFileChange> selected = new HashMap<>();
        Set<String> foldedPaths = new HashSet<>();
        for (Integer index : accepted) {
            CandidateFileChange change = changes.get(index);
            String path = new ProjectRelativePath(change.relativePath().value()).value();
            if (selected.putIfAbsent(path, change) != null
                    || !foldedPaths.add(path.toLowerCase(java.util.Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Candidate selection contains duplicate or ambiguous paths");
            }
        }
        List<ProjectObjectEntry> result = new ArrayList<>();
        for (ProjectObjectEntry entry : base.entries()) {
            CandidateFileChange change = selected.remove(entry.path());
            if (change != null && change.type() == CandidateFileChange.Type.ADD) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Candidate ADD target already exists in the current Project");
            }
            if (change != null && (change.baseFileHash() == null
                    || !change.baseFileHash().sha256().equals(entry.sha256()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Candidate base file hash changed");
            }
            if (change != null && change.type() == CandidateFileChange.Type.DELETE) continue;
            byte[] content = change == null ? storage.readFile(basePrefix, entry.path(), properties.getMaxFileBytes())
                    : change.candidateText().text().getBytes(StandardCharsets.UTF_8);
            if (change == null && (content.length != entry.sizeBytes() || !sha256(content).equals(entry.sha256()))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Project base revision object is incomplete");
            }
            result.add(storeChecked(resultPrefix, entry.path(), content));
        }
        for (CandidateFileChange change : selected.values()) {
            if (change.type() != CandidateFileChange.Type.ADD) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Candidate target no longer exists in the current Project");
            }
            result.add(storeChecked(resultPrefix, change.relativePath().value(),
                    change.candidateText().text().getBytes(StandardCharsets.UTF_8)));
        }
        result.sort(Comparator.comparing(ProjectObjectEntry::path));
        TrustedManifest expected = validateManifest(result);
        storage.writeManifest(resultPrefix, expected.entries());
        TrustedManifest restored = trustedManifest(resultPrefix);
        if (!sameManifest(expected.entries(), restored.entries()) || !expected.version().equals(restored.version())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Project revision manifest verification failed");
        }
        verifyStoredFiles(resultPrefix, restored.entries());
        return restored;
    }

    private ProjectObjectEntry storeChecked(String prefix, String path, byte[] content) {
        if (content.length > properties.getMaxFileBytes()) {
            throw new ProjectTraversalLimitException("Project revision file budget exceeded");
        }
        ProjectObjectEntry stored = storage.storeBytes(prefix, new ProjectRelativePath(path).value(), content,
                "text/plain; charset=utf-8");
        String hash = sha256(content);
        if (stored.sizeBytes() != content.length || !hash.equals(stored.sha256())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Project revision object verification failed");
        }
        return stored;
    }

    private TrustedManifest trustedManifest(String prefix) { return validateManifest(storage.readManifest(prefix)); }

    private TrustedManifest validateManifest(List<ProjectObjectEntry> input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Project manifest is missing");
        if (input.size() > properties.getMaxFiles()) throw new ProjectTraversalLimitException("Project file-count budget exceeded");
        List<ProjectObjectEntry> sorted = new ArrayList<>();
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        for (ProjectObjectEntry entry : input) {
            if (entry == null || entry.sizeBytes() < 0 || entry.sizeBytes() > properties.getMaxFileBytes()
                    || entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Project manifest is invalid");
            }
            String path = new ProjectRelativePath(entry.path()).value();
            if (!exact.add(path) || !folded.add(path.toLowerCase(java.util.Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Project manifest contains duplicate or ambiguous paths");
            }
            if (entry.sizeBytes() > properties.getMaxTotalBytes() - total) {
                throw new ProjectTraversalLimitException("Project byte budget exceeded");
            }
            total += entry.sizeBytes();
            sorted.add(new ProjectObjectEntry(path, entry.sizeBytes(), entry.modifiedAt(), entry.sha256()));
        }
        sorted.sort(Comparator.comparing(ProjectObjectEntry::path));
        String version = ProjectManifestIdentity.derive(sorted.stream().map(entry ->
                new ProjectManifestIdentity.Entry(new ProjectRelativePath(entry.path()),
                        new FileHash(entry.sha256()), entry.sizeBytes())).toList()).value();
        return new TrustedManifest(List.copyOf(sorted), version, total);
    }

    private void verifyStoredFiles(String prefix, List<ProjectObjectEntry> entries) {
        for (ProjectObjectEntry entry : entries) {
            byte[] content = storage.readFile(prefix, entry.path(), properties.getMaxFileBytes());
            if (content.length != entry.sizeBytes() || !sha256(content).equals(entry.sha256())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Project revision object is incomplete");
            }
        }
    }

    private TrustedManifest requireCompleteRevision(ProjectRevision revision) {
        TrustedManifest manifest = trustedManifest(revision.getObjectPrefix());
        if (!revision.getProjectVersion().equals(manifest.version())
                || revision.getFileCount() != manifest.entries().size()
                || revision.getTotalBytes() != manifest.totalBytes()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Project revision is incomplete");
        }
        verifyStoredFiles(revision.getObjectPrefix(), manifest.entries());
        return manifest;
    }

    private ProjectRevision ensureCurrentRevision(Project project, TrustedManifest manifest) {
        if (project.getCurrentRevisionId() != null) {
            ProjectRevision revision = revisions.findByIdAndProjectIdAndUserId(project.getCurrentRevisionId(),
                    project.getId(), project.getUserId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Project current revision is unavailable"));
            if (!revision.getObjectPrefix().equals(project.getRootPath())
                    || !revision.getProjectVersion().equals(manifest.version())
                    || revision.getFileCount() != manifest.entries().size()
                    || revision.getTotalBytes() != manifest.totalBytes()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Project current revision changed");
            }
            return revision;
        }
        ProjectRevision initial = revisions.saveAndFlush(new ProjectRevision(project.getId(), project.getUserId(),
                manifest.version(), project.getRootPath(), manifest.entries().size(), manifest.totalBytes(),
                ProjectRevision.SourceType.UPLOAD, null));
        project.publishRevision(initial);
        projects.saveAndFlush(project);
        return initial;
    }

    private void requireCurrent(Project project, ReservedOperation reserved, TrustedManifest current) {
        if (!reserved.baseRevisionId().equals(project.getCurrentRevisionId())
                || !current.version().equals(operations.findById(reserved.operationId()).orElseThrow().getBaseVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project current version changed");
        }
    }

    private Project requireManagedProject(Long userId, Long projectId) {
        Project project = projects.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        requireManaged(project);
        return project;
    }

    private Project lockedManagedProject(Long userId, Long projectId) {
        Project project = projects.findLockedByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        requireManaged(project);
        return project;
    }

    private void requireManaged(Project project) {
        if (project.getRootType() != ProjectRootType.MINIO_OBJECTS) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only browser-uploaded managed Projects support revisions");
        }
    }

    private ProjectRevision requireRevision(Long userId, Long projectId, Long revisionId) {
        return revisions.findByIdAndProjectIdAndUserId(revisionId, projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project revision not found"));
    }

    private ProjectRevisionOperation findReplay(Long userId, Long projectId, String key, String requestHash) {
        ProjectRevisionOperation operation = operations
                .findByUserIdAndProjectIdAndIdempotencyKey(userId, projectId, key).orElse(null);
        if (operation != null) requireSamePayload(operation, requestHash);
        return operation;
    }

    private void requireSamePayload(ProjectRevisionOperation operation, String requestHash) {
        if (!operation.getRequestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used for a different revision request");
        }
    }

    private ProjectRevisionOperationResponse replay(ProjectRevisionOperation operation) {
        if (operation.getOutcome() == ProjectRevisionOperation.Outcome.STARTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Revision operation is still in progress");
        }
        if (operation.getOutcome() == ProjectRevisionOperation.Outcome.FAILED) {
            int status = parseStatus(operation.getErrorCode());
            throw new ResponseStatusException(HttpStatus.valueOf(status), "Revision operation previously failed");
        }
        return response(operation);
    }

    private ProjectRevisionOperationResponse response(ProjectRevisionOperation operation) {
        return new ProjectRevisionOperationResponse(operation.getId(), operation.getOperationType(),
                operation.getOutcome(), operation.getBaseRevisionId(), operation.getBaseVersion(),
                operation.getResultRevisionId(), operation.getResultVersion(), operation.getCandidateArtifactId(),
                operation.getCandidateFingerprint(), integers(operation.getAcceptedChangeIndexes()),
                integers(operation.getRejectedChangeIndexes()), operation.getCompletedAt());
    }

    private void fail(Long operationId, String code) {
        if (operationId == null) return;
        transactions.executeWithoutResult(status -> operations.findById(operationId).ifPresent(operation -> {
            if (operation.getOutcome() == ProjectRevisionOperation.Outcome.STARTED) {
                operation.fail(code);
                operations.saveAndFlush(operation);
            }
        }));
    }

    private String errorCode(RuntimeException error) {
        if (error instanceof ResponseStatusException response) return "HTTP_" + response.getStatusCode().value();
        if (error instanceof ProjectTraversalLimitException) return "HTTP_413";
        return "HTTP_500";
    }

    private int parseStatus(String code) {
        try { return Integer.parseInt(code == null ? "500" : code.replace("HTTP_", "")); }
        catch (RuntimeException ignored) { return 500; }
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key header is required");
        }
        return value;
    }

    private String requireIfMatch(String value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a Project version");
        }
        return normalized;
    }

    private List<Integer> sortedDistinctNonEmpty(List<Integer> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one Candidate change must be selected");
        }
        TreeSet<Integer> sorted = new TreeSet<>(values);
        if (sorted.size() != values.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate change selection contains duplicates");
        }
        return List.copyOf(sorted);
    }

    private void requireAcceptedIndexes(int size, List<Integer> accepted) {
        if (accepted.stream().anyMatch(index -> index >= size)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Candidate change selection is outside the server Candidate");
        }
    }

    private List<Integer> complement(int size, List<Integer> accepted) {
        Set<Integer> selected = Set.copyOf(accepted);
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < size; index++) if (!selected.contains(index)) result.add(index);
        return List.copyOf(result);
    }

    private String requestHash(String type, Long routeId, String expectedVersion, List<Integer> indexes) {
        return sha256((type + "\0" + routeId + "\0" + expectedVersion + "\0" + indexes)
                .getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private String json(List<Integer> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception ex) { throw new IllegalStateException("Failed to serialize revision decision", ex); }
    }

    private List<Integer> integers(String value) {
        try { return List.copyOf(objectMapper.readValue(value, INTEGER_LIST)); }
        catch (Exception ex) { throw new IllegalStateException("Stored revision decision is invalid", ex); }
    }

    private boolean sameManifest(List<ProjectObjectEntry> left, List<ProjectObjectEntry> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            ProjectObjectEntry a = left.get(index), b = right.get(index);
            if (!a.path().equals(b.path()) || a.sizeBytes() != b.sizeBytes() || !a.sha256().equals(b.sha256())) return false;
        }
        return true;
    }

    private void requireIdentity(Long userId, Long projectId) {
        if (userId == null || userId < 1 || projectId == null || projectId < 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }

    private record TrustedManifest(List<ProjectObjectEntry> entries, String version, long totalBytes) { }
    private record ReservedOperation(Long operationId, Long baseRevisionId, String basePrefix,
                                     ProjectRevisionOperation replay) { }
}
