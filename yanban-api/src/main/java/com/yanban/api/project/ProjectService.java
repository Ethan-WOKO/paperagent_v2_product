package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.effect.project.V2ProjectStructuredReadFacade;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxWorkspaceRef;
import com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final int MAX_SEARCH_LINE_LENGTH = 2_000;
    private static final String LOWER_SHA_256 = "[a-f0-9]{64}";
    private final ProjectRepository projects;
    private final List<ProjectRootProvider> rootProviders;
    private final ProjectStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final ProjectObjectStorage objectStorage;
    private final V2ProjectStructuredReadFacade structuredReads;

    public ProjectService(ProjectRepository projects,
                          List<ProjectRootProvider> rootProviders,
                          ProjectStorageProperties properties,
                          ObjectMapper objectMapper) {
        this(projects, rootProviders, properties, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectService(ProjectRepository projects,
                          List<ProjectRootProvider> rootProviders,
                          ProjectStorageProperties properties,
                          ObjectMapper objectMapper,
                          ProjectObjectStorage objectStorage) {
        this.projects = projects;
        this.rootProviders = List.copyOf(rootProviders);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.structuredReads = new V2ProjectStructuredReadFacade(objectMapper);
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryResponse> list(Long userId) {
        return projects.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(ProjectSummaryResponse::from).toList();
    }

    @Transactional
    public ProjectSummaryResponse rename(Long userId, Long projectId, String requestedName) {
        Project project = ownedProject(userId, projectId);
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }
        if (name.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name must not exceed 255 characters");
        }
        project.rename(name);
        return ProjectSummaryResponse.from(projects.saveAndFlush(project));
    }

    /** Local bindings stay untouched; server-owned upload snapshots are removed only after the DB commit succeeds. */
    @Transactional
    public void delete(Long userId, Long projectId) {
        Project project = ownedProject(userId, projectId);
        Path managedSnapshot = resolveManagedSnapshotForCleanup(project);
        Runnable objectCleanup = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? () -> deleteObjectSnapshot(project) : null;
        projects.delete(project);
        if (managedSnapshot != null) {
            Runnable cleanup = () -> deleteManagedSnapshot(managedSnapshot);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cleanup.run();
                    }
                });
            } else {
                cleanup.run();
            }
        }
        if (objectCleanup != null) scheduleAfterCommit(objectCleanup);
    }

    private Path resolveManagedSnapshotForCleanup(Project project) {
        if (project.getRootType() != ProjectRootType.MANAGED_UPLOAD) {
            return null;
        }
        try {
            return rootProviderFor(ProjectRootType.MANAGED_UPLOAD).resolve(project).canonicalPath();
        } catch (RuntimeException ex) {
            log.warn("Managed Project snapshot could not be resolved for cleanup projectId={}", project.getId());
            return null;
        }
    }

    private void deleteManagedSnapshot(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.warn("Managed Project snapshot cleanup failed");
        }
    }

    private void deleteObjectSnapshot(Project project) {
        try {
            requireObjectStorage().deletePrefix(project.getRootPath());
        } catch (RuntimeException ex) {
            log.warn("MinIO Project snapshot cleanup failed projectId={}", project.getId());
        }
    }

    private void scheduleAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    @Transactional(readOnly = true)
    public ProjectManifestResponse manifest(Long userId, Long projectId) {
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        List<ProjectFileEntry> files = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? listObjectFiles(project, policy)
                : listFiles(resolveLocal(project, policy));
        return manifestResponse(projectId, files);
    }

    /**
     * Builds one immutable manifest snapshot and reads only explicitly requested admitted text files.
     * The returned value contains portable paths and content only, never a host root or object key.
     */
    @Transactional(readOnly = true)
    public SandboxWorkspaceMaterialization materializeSandbox(Long userId, Long projectId,
                                                               Set<String> requestedRelativePaths) {
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        List<ProjectObjectEntry> objectEntries = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? List.copyOf(requireObjectStorage().readManifest(project.getRootPath())) : List.of();
        ResolvedProject local = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? null : resolveLocal(project, policy);
        List<ProjectFileEntry> files = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? listObjectFiles(project, policy, objectEntries)
                : listFiles(local);
        ProjectManifestResponse manifest = manifestResponse(projectId, files);
        SandboxWorkspaceSnapshot snapshot = snapshot(projectId, manifest, files);

        Map<String, ProjectFileEntry> admitted = new LinkedHashMap<>();
        files.forEach(file -> admitted.put(file.path(), file));
        Map<String, String> textFiles = new LinkedHashMap<>();
        Set<String> requested = requestedRelativePaths == null ? Set.of() : new TreeSet<>(requestedRelativePaths);
        for (String value : requested) {
            String portablePath = new ProjectRelativePath(value).value();
            ProjectFileEntry expected = admitted.get(portablePath);
            if (expected == null) continue;
            if (ProjectAssetAdmissionPolicy.readOnlyBinaryPath(portablePath)) {
                continue;
            }
            ProjectFileResponse read = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                    ? readObjectFile(project, policy, portablePath, objectEntries)
                    : readLocalFile(local, portablePath);
            if (read.sizeBytes() != expected.sizeBytes() || !read.sha256().equals(expected.sha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Project changed while materializing the sandbox snapshot");
            }
            textFiles.put(portablePath, read.content());
        }
        List<ProjectFileEntry> currentFiles = project.getRootType() == ProjectRootType.MINIO_OBJECTS
                ? listObjectFiles(project, policy,
                        List.copyOf(requireObjectStorage().readManifest(
                                project.getRootPath())))
                : listFiles(local);
        ProjectManifestResponse currentManifest = manifestResponse(projectId, currentFiles);
        if (!manifest.version().equals(currentManifest.version()) || !files.equals(currentFiles)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project changed while materializing the sandbox snapshot");
        }
        return new SandboxWorkspaceMaterialization(snapshot,
                Collections.unmodifiableMap(new LinkedHashMap<>(textFiles)));
    }

    /**
     * Materializes exact admitted bytes for the authenticated V2 source adapter.
     * This method is intentionally not exposed by a Controller.
     */
    @Transactional(readOnly = true)
    public ProjectVersionByteMaterialization materializeVersionBytes(
            Long userId,
            Long projectId,
            Set<String> requestedRelativePaths) {
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        List<ProjectObjectEntry> objectEntries =
                project.getRootType() == ProjectRootType.MINIO_OBJECTS
                        ? List.copyOf(requireObjectStorage().readManifest(
                                project.getRootPath()))
                        : List.of();
        ResolvedProject local = project.getRootType()
                == ProjectRootType.MINIO_OBJECTS
                        ? null : resolveLocal(project, policy);
        List<ProjectFileEntry> files = project.getRootType()
                == ProjectRootType.MINIO_OBJECTS
                        ? listObjectFiles(project, policy, objectEntries)
                        : listFiles(local);
        ProjectManifestResponse manifest = manifestResponse(projectId, files);
        SandboxWorkspaceSnapshot snapshot = snapshot(projectId, manifest, files);

        Map<String, ProjectFileEntry> admitted = new LinkedHashMap<>();
        files.forEach(file -> admitted.put(file.path(), file));
        Map<String, byte[]> selected = new LinkedHashMap<>();
        Set<String> requested = requestedRelativePaths == null
                ? Set.of() : new TreeSet<>(requestedRelativePaths);
        for (String value : requested) {
            String portablePath = new ProjectRelativePath(value).value();
            ProjectFileEntry expected = admitted.get(portablePath);
            if (expected == null) {
                continue;
            }
            byte[] content = project.getRootType()
                    == ProjectRootType.MINIO_OBJECTS
                            ? readObjectAsset(project, policy, portablePath,
                                    objectEntries)
                            : readLocalAsset(local, portablePath);
            if (content.length != expected.sizeBytes()
                    || !sha256(content).equals(expected.sha256())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Project changed while materializing the V2 snapshot");
            }
            selected.put(portablePath, content);
        }

        List<ProjectFileEntry> currentFiles = project.getRootType()
                == ProjectRootType.MINIO_OBJECTS
                        ? listObjectFiles(project, policy,
                                List.copyOf(requireObjectStorage().readManifest(
                                        project.getRootPath())))
                        : listFiles(local);
        ProjectManifestResponse current = manifestResponse(
                projectId, currentFiles);
        if (!manifest.version().equals(current.version())
                || !files.equals(currentFiles)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project changed while materializing the V2 snapshot");
        }
        return new ProjectVersionByteMaterialization(snapshot, selected);
    }

    @Transactional(readOnly = true)
    public ProjectFileResponse readFile(Long userId, Long projectId, String relativePath) {
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        if (project.getRootType() == ProjectRootType.MINIO_OBJECTS) {
            return readObjectFile(project, policy, relativePath);
        }
        return readLocalFile(resolveLocal(project, policy), relativePath);
    }

    private ProjectFileResponse readLocalFile(ResolvedProject resolved, String relativePath) {
        final Path realPath;
        try {
            realPath = ProjectPathGuard.resolveExistingFile(resolved.root(), relativePath);
        } catch (ProjectFileUnavailableException ex) {
            throw inaccessibleFile();
        }
        try {
            Path relative = resolved.root().canonicalPath().relativize(realPath);
            BasicFileAttributes attributes = Files.readAttributes(realPath, BasicFileAttributes.class);
            if (!resolved.policy().allows(relative) || !attributes.isRegularFile()
                    || attributes.size() > properties.getMaxFileBytes()
                    || ProjectAssetAdmissionPolicy.readOnlyBinaryPath(
                            toPortablePath(relative))) {
                throw inaccessibleFile();
            }
            byte[] content = readBoundedContent(realPath, attributes.size());
            if (!ProjectAssetAdmissionPolicy.readableText(content)) {
                throw inaccessibleFile();
            }
            return new ProjectFileResponse(toPortablePath(relative),
                    decodeUtf8Strict(content), content.length,
                    Files.getLastModifiedTime(realPath).toInstant(), sha256(content));
        } catch (IOException ex) {
            throw inaccessibleFile();
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectSearchHit> search(Long userId, Long projectId, String query, Integer requestedMaxResults) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        Project project = ownedProject(userId, projectId);
        ProjectPathPolicy policy = ProjectPathPolicy.from(project, objectMapper);
        int maxResults = requestedMaxResults == null ? 20 : Math.max(1, Math.min(requestedMaxResults, properties.getMaxSearchResults()));
        if (project.getRootType() == ProjectRootType.MINIO_OBJECTS) {
            return searchObjectFiles(project, policy, query, maxResults);
        }
        ResolvedProject resolved = resolveLocal(project, policy);
        List<ProjectSearchHit> hits = new ArrayList<>();
        for (ProjectFileEntry entry : listFiles(resolved)) {
            if (hits.size() >= maxResults) {
                break;
            }
            if (ProjectAssetAdmissionPolicy.readOnlyBinaryPath(entry.path())) {
                continue;
            }
            Path file = ProjectPathGuard.resolveExistingFile(resolved.root(), entry.path());
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                if (attributes.size() > properties.getMaxFileBytes()) {
                    continue;
                }
                byte[] content = readBoundedContent(file, attributes.size());
                if (!ProjectAssetAdmissionPolicy.readableText(content)) {
                    continue;
                }
                String[] lines = new String(content, StandardCharsets.UTF_8)
                        .split("\\R", -1);
                for (int index = 0; index < lines.length && hits.size() < maxResults; index++) {
                    if (lines[index].contains(query)) {
                        hits.add(new ProjectSearchHit(entry.path(), index + 1,
                                abbreviate(lines[index]), entry.sha256()));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // A changed or malformed file is skipped; it must not escape the authorized root.
            }
        }
        return hits;
    }

    private Project ownedProject(Long userId, Long projectId) {
        Project project = projects.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (project.getAccessMode() != ProjectAccessMode.READ_ONLY) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project is not read-only");
        }
        return project;
    }

    private ResolvedProject resolveLocal(Project project, ProjectPathPolicy policy) {
        if (project.getRootType() == ProjectRootType.MINIO_OBJECTS) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project object root is not a local path");
        }
        return new ResolvedProject(rootProviderFor(project.getRootType()).resolve(project), policy);
    }

    private ProjectRootProvider rootProviderFor(ProjectRootType rootType) {
        return rootProviders.stream()
                .filter(provider -> provider.supportedType() == rootType)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Project root provider is unavailable"));
    }

    private List<ProjectFileEntry> listFiles(ResolvedProject resolved) {
        List<ProjectFileEntry> files = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        try {
            Files.walkFileTree(resolved.root().canonicalPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(resolved.root().canonicalPath()) && Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = resolved.root().canonicalPath().relativize(dir);
                    if (relative.getNameCount() > properties.getMaxTraversalDepth()) {
                        throw new ProjectTraversalLimitException("Project traversal depth exceeded");
                    }
                    if (resolved.policy().shouldSkipDirectory(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    budget.recordVisitedFile();
                    if (attrs.size() > properties.getMaxFileBytes()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = resolved.root().canonicalPath().relativize(file);
                    if (!resolved.policy().allows(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path realPath = file.toRealPath();
                    if (!realPath.startsWith(resolved.root().canonicalPath())) {
                        return FileVisitResult.CONTINUE;
                    }
                    byte[] content = readBoundedContent(realPath, attrs.size());
                    if (!ProjectAssetAdmissionPolicy.admits(
                            toPortablePath(relative), content)) {
                        return FileVisitResult.CONTINUE;
                    }
                    budget.recordAdmittedBytes(attrs.size());
                    files.add(new ProjectFileEntry(toPortablePath(relative), content.length,
                            Files.getLastModifiedTime(realPath).toInstant(), sha256(content)));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ProjectTraversalLimitException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project files are unavailable", ex);
        }
        files.sort(Comparator.comparing(ProjectFileEntry::path));
        return files;
    }

    private List<ProjectFileEntry> listObjectFiles(Project project, ProjectPathPolicy policy) {
        return listObjectFiles(project, policy,
                requireObjectStorage().readManifest(project.getRootPath()));
    }

    private List<ProjectFileEntry> listObjectFiles(
            Project project,
            ProjectPathPolicy policy,
            List<ProjectObjectEntry> objectEntries) {
        List<ProjectFileEntry> files = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        for (ProjectObjectEntry entry : objectEntries) {
            if (entry == null || entry.path() == null
                    || entry.sizeBytes() < 0
                    || entry.modifiedAt() == null
                    || entry.sha256() == null
                    || !entry.sha256().matches(LOWER_SHA_256)) {
                continue;
            }
            Path relative = ProjectPathGuard.parseRelative(entry.path(), "Project object path");
            if (relative.getNameCount() > properties.getMaxTraversalDepth()) {
                throw new ProjectTraversalLimitException("Project traversal depth exceeded");
            }
            if (!policy.allows(relative)) continue;
            budget.recordVisitedFile();
            if (entry.sizeBytes() > properties.getMaxFileBytes()) continue;
            if (ProjectAssetAdmissionPolicy.readOnlyBinaryPath(entry.path())) {
                byte[] content = requireObjectStorage().readFile(
                        project.getRootPath(), entry.path(),
                        properties.getMaxFileBytes());
                if (content == null
                        || content.length != entry.sizeBytes()
                        || !sha256(content).equals(entry.sha256())
                        || !ProjectAssetAdmissionPolicy.admits(
                                entry.path(), content)) {
                    continue;
                }
            }
            budget.recordAdmittedBytes(entry.sizeBytes());
            files.add(entry.toFileEntry());
        }
        files.sort(Comparator.comparing(ProjectFileEntry::path));
        return List.copyOf(files);
    }

    private byte[] readLocalAsset(ResolvedProject resolved,
                                  String relativePath) {
        final Path realPath;
        try {
            realPath = ProjectPathGuard.resolveExistingFile(
                    resolved.root(), relativePath);
        } catch (ProjectFileUnavailableException ex) {
            throw inaccessibleFile();
        }
        try {
            Path relative = resolved.root().canonicalPath()
                    .relativize(realPath);
            BasicFileAttributes attributes = Files.readAttributes(
                    realPath, BasicFileAttributes.class);
            String portablePath = toPortablePath(relative);
            if (!realPath.startsWith(resolved.root().canonicalPath())
                    || !resolved.policy().allows(relative)
                    || !attributes.isRegularFile()
                    || attributes.size() > properties.getMaxFileBytes()) {
                throw inaccessibleFile();
            }
            byte[] content = readBoundedContent(
                    realPath, attributes.size());
            if (!ProjectAssetAdmissionPolicy.admits(
                    portablePath, content)) {
                throw inaccessibleFile();
            }
            return content;
        } catch (IOException ex) {
            throw inaccessibleFile();
        }
    }

    private byte[] readObjectAsset(Project project,
                                   ProjectPathPolicy policy,
                                   String relativePath,
                                   List<ProjectObjectEntry> objectEntries) {
        Path relative = ProjectPathGuard.parseRelative(
                relativePath, "path");
        if (!policy.allows(relative)) {
            throw inaccessibleFile();
        }
        String portablePath = toPortablePath(relative);
        ProjectObjectEntry entry = objectEntries.stream()
                .filter(item -> portablePath.equals(item.path()))
                .findFirst().orElseThrow(this::inaccessibleFile);
        if (entry.sizeBytes() > properties.getMaxFileBytes()) {
            throw inaccessibleFile();
        }
        byte[] content = requireObjectStorage().readFile(
                project.getRootPath(), portablePath,
                properties.getMaxFileBytes());
        if (content == null
                || content.length != entry.sizeBytes()
                || !sha256(content).equals(entry.sha256())
                || !ProjectAssetAdmissionPolicy.admits(
                        portablePath, content)) {
            throw inaccessibleFile();
        }
        return content;
    }

    private ProjectFileResponse readObjectFile(Project project, ProjectPathPolicy policy, String relativePath) {
        return readObjectFile(project, policy, relativePath,
                requireObjectStorage().readManifest(project.getRootPath()));
    }

    private ProjectFileResponse readObjectFile(Project project, ProjectPathPolicy policy, String relativePath,
                                               List<ProjectObjectEntry> objectEntries) {
        Path relative = ProjectPathGuard.parseRelative(relativePath, "path");
        if (!policy.allows(relative)) throw inaccessibleFile();
        String portablePath = toPortablePath(relative);
        if (ProjectAssetAdmissionPolicy.readOnlyBinaryPath(portablePath)) {
            throw inaccessibleFile();
        }
        ProjectObjectEntry entry = objectEntries.stream()
                .filter(item -> portablePath.equals(item.path()))
                .findFirst().orElseThrow(this::inaccessibleFile);
        if (entry.sizeBytes() > properties.getMaxFileBytes()) throw inaccessibleFile();
        byte[] content = requireObjectStorage().readFile(project.getRootPath(), portablePath,
                properties.getMaxFileBytes());
        if (content.length != entry.sizeBytes()
                || !sha256(content).equals(entry.sha256())
                || !ProjectAssetAdmissionPolicy.readableText(content)) {
            throw inaccessibleFile();
        }
        return new ProjectFileResponse(portablePath, decodeUtf8Strict(content),
                content.length, entry.modifiedAt(), entry.sha256());
    }

    @Transactional(readOnly = true)
    public ProjectFileResponse previewFile(
            Long userId, Long projectId, String relativePath) {
        String path = new ProjectRelativePath(relativePath).value();
        if (!ProjectAssetAdmissionPolicy.readOnlyBinaryPath(path)) {
            return readFile(userId, projectId, path);
        }
        ProjectManifestResponse manifest = manifest(userId, projectId);
        ProjectFileEntry entry = manifest.files().stream()
                .filter(candidate -> candidate.path().equals(path))
                .findFirst().orElseThrow(this::inaccessibleFile);
        ProjectVersionByteMaterialization materialized =
                materializeVersionBytes(userId, projectId, Set.of(path));
        if (!manifest.version().equals(materialized.snapshot().workspace()
                .projectVersion().value())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project changed while preparing the file preview");
        }
        byte[] content = materialized.files().get(path);
        if (!ProjectAssetAdmissionPolicy.admits(path, content)
                || !structuredReads.supports(path)) {
            throw inaccessibleFile();
        }
        try {
            var extracted = structuredReads.readBytes(path, content);
            return new ProjectFileResponse(path, extracted.content(),
                    content.length, entry.modifiedAt(), sha256(content));
        } catch (V2ProjectStructuredReadFacade.ReadException failure) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Project document could not be parsed safely: "
                            + failure.stage());
        }
    }

    @Transactional(readOnly = true)
    public ProjectRawFileResponse readRawFile(
            Long userId, Long projectId, String relativePath) {
        String path = new ProjectRelativePath(relativePath).value();
        if (!ProjectAssetAdmissionPolicy.readOnlyBinaryPath(path)) {
            throw inaccessibleFile();
        }
        ProjectVersionByteMaterialization materialized =
                materializeVersionBytes(userId, projectId, Set.of(path));
        byte[] content = materialized.files().get(path);
        if (content == null || !ProjectAssetAdmissionPolicy.admits(path, content)) {
            throw inaccessibleFile();
        }
        return new ProjectRawFileResponse(path, mediaType(path), content);
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    public record ProjectRawFileResponse(
            String path, String mediaType, byte[] content) {
        public ProjectRawFileResponse {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private List<ProjectSearchHit> searchObjectFiles(Project project, ProjectPathPolicy policy,
                                                     String query, int maxResults) {
        List<ProjectSearchHit> hits = new ArrayList<>();
        for (ProjectFileEntry entry : listObjectFiles(project, policy)) {
            if (hits.size() >= maxResults) break;
            if (ProjectAssetAdmissionPolicy.readOnlyBinaryPath(entry.path())) {
                continue;
            }
            byte[] content = requireObjectStorage().readFile(project.getRootPath(), entry.path(),
                    properties.getMaxFileBytes());
            if (content.length != entry.sizeBytes() || !sha256(content).equals(entry.sha256())
                    || !ProjectAssetAdmissionPolicy.readableText(content)) {
                continue;
            }
            String[] lines = new String(content, StandardCharsets.UTF_8).split("\\R", -1);
            for (int index = 0; index < lines.length && hits.size() < maxResults; index++) {
                if (lines[index].contains(query)) {
                    hits.add(new ProjectSearchHit(entry.path(), index + 1,
                            abbreviate(lines[index]), entry.sha256()));
                }
            }
        }
        return hits;
    }

    private ProjectObjectStorage requireObjectStorage() {
        if (objectStorage == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Project object storage is not configured");
        }
        return objectStorage;
    }

    private byte[] readBoundedContent(Path file, long expectedSize) throws IOException {
        if (expectedSize < 0 || expectedSize > properties.getMaxFileBytes() || expectedSize > Integer.MAX_VALUE) {
            throw new IOException("Project file exceeds the read budget");
        }
        ByteBuffer content = ByteBuffer.allocate((int) expectedSize);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            while (content.hasRemaining() && channel.read(content) > 0) {
                // Fill the pre-sized buffer without allocating beyond the checked size.
            }
            if (channel.read(ByteBuffer.allocate(1)) > 0) {
                throw new IOException("Project file changed beyond the read budget");
            }
        }
        if (content.position() == content.capacity()) {
            return content.array();
        }
        byte[] truncated = new byte[content.position()];
        System.arraycopy(content.array(), 0, truncated, 0, truncated.length);
        return truncated;
    }

    private ProjectManifestResponse manifestResponse(Long projectId, List<ProjectFileEntry> files) {
        String version = ProjectManifestIdentity.derive(files.stream()
                .map(file -> new ProjectManifestIdentity.Entry(new ProjectRelativePath(file.path()),
                        new FileHash(file.sha256()), file.sizeBytes())).toList()).value();
        return new ProjectManifestResponse(projectId, version, files);
    }

    private SandboxWorkspaceSnapshot snapshot(
            Long projectId,
            ProjectManifestResponse manifest,
            List<ProjectFileEntry> files) {
        ProjectVersionRef version = new ProjectVersionRef(
                manifest.version());
        return new SandboxWorkspaceSnapshot(
                new SandboxWorkspaceRef(projectId, version),
                files.stream().map(file -> new SandboxFileSnapshot(
                        new ProjectRelativePath(file.path()),
                        new FileHash(file.sha256()),
                        file.sizeBytes())).toList());
    }

    private String decodeUtf8Strict(byte[] content) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Sandbox text file is not valid UTF-8");
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String abbreviate(String line) {
        return line.length() <= MAX_SEARCH_LINE_LENGTH ? line : line.substring(0, MAX_SEARCH_LINE_LENGTH);
    }

    private ResponseStatusException inaccessibleFile() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Project file not found");
    }

    private record ResolvedProject(ProjectRoot root, ProjectPathPolicy policy) {
    }

    public record SandboxWorkspaceMaterialization(SandboxWorkspaceSnapshot snapshot,
                                                  Map<String, String> textFiles) {
        public SandboxWorkspaceMaterialization {
            if (snapshot == null || textFiles == null) {
                throw new IllegalArgumentException("sandbox materialization is incomplete");
            }
            textFiles = Collections.unmodifiableMap(new LinkedHashMap<>(textFiles));
        }
    }

    public static final class ProjectVersionByteMaterialization {
        private final SandboxWorkspaceSnapshot snapshot;
        private final Map<String, byte[]> files;

        public ProjectVersionByteMaterialization(
                SandboxWorkspaceSnapshot snapshot,
                Map<String, byte[]> files) {
            if (snapshot == null || files == null) {
                throw new IllegalArgumentException(
                        "Project version materialization is incomplete");
            }
            this.snapshot = snapshot;
            this.files = copyBytes(files);
        }

        public SandboxWorkspaceSnapshot snapshot() {
            return snapshot;
        }

        public Map<String, byte[]> files() {
            return copyBytes(files);
        }

        private static Map<String, byte[]> copyBytes(
                Map<String, byte[]> source) {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            source.forEach((path, content) -> {
                if (path == null || content == null) {
                    throw new IllegalArgumentException(
                            "Project version materialization contains a null entry");
                }
                copy.put(path, content.clone());
            });
            return Collections.unmodifiableMap(copy);
        }
    }

    private final class TraversalBudget {
        private int visitedFiles;
        private long admittedBytes;

        void recordVisitedFile() {
            visitedFiles++;
            if (visitedFiles > properties.getMaxFiles()) {
                throw new ProjectTraversalLimitException("Project file-count budget exceeded");
            }
        }

        void recordAdmittedBytes(long bytes) {
            if (bytes > properties.getMaxTotalBytes() - admittedBytes) {
                throw new ProjectTraversalLimitException("Project byte budget exceeded");
            }
            admittedBytes += bytes;
        }
    }
}
