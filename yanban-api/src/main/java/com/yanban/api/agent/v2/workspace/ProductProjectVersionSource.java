package com.yanban.api.agent.v2.workspace;

import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectService.SandboxWorkspaceMaterialization;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSource;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapts one owner-qualified immutable product Project cut to the stable V2 source contract.
 */
final class ProductProjectVersionSource implements ProjectVersionSource {

    private static final String OPERATION = "loadProjectVersion";
    private static final String SHA_256 = "sha256";
    private static final String LOWER_SHA_256 = "[a-f0-9]{64}";

    private final Long userId;
    private final Long projectId;
    private final String frozenVersionId;
    private final ProjectVersionRef boundReference;
    private final ProjectService projects;

    ProductProjectVersionSource(
            Long userId,
            Long projectId,
            String frozenVersionId,
            ProjectService projects
    ) {
        if (userId == null || userId <= 0 || projectId == null || projectId <= 0
                || frozenVersionId == null || frozenVersionId.isBlank() || projects == null) {
            throw sourceFailure();
        }
        this.userId = userId;
        this.projectId = projectId;
        this.frozenVersionId = frozenVersionId;
        this.boundReference = new ProjectVersionRef(String.valueOf(projectId), frozenVersionId);
        this.projects = projects;
    }

    @Override
    public ProjectVersionSnapshot load(ProjectVersionRef version) {
        if (!boundReference.equals(version)) {
            throw new WorkspaceException(WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH, OPERATION);
        }

        ProjectManifestResponse manifest = projects.manifest(userId, projectId);
        List<ManifestFile> expected = validateManifest(manifest);
        Set<String> paths = new LinkedHashSet<>();
        expected.forEach(file -> paths.add(file.path()));

        SandboxWorkspaceMaterialization materialized =
                projects.materializeSandbox(userId, projectId, paths);
        validateMaterialization(materialized, expected);

        Map<String, String> textFiles = materialized.textFiles();
        List<ProjectFileSnapshot> files = new ArrayList<>(expected.size());
        for (ManifestFile expectedFile : expected) {
            String content = textFiles.get(expectedFile.path());
            if (content == null) {
                throw sourceFailure();
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length != expectedFile.sizeBytes()
                    || !sha256(bytes).equals(expectedFile.sha256())) {
                throw sourceFailure();
            }
            files.add(new ProjectFileSnapshot(
                    new ProjectPath(expectedFile.path()),
                    bytes,
                    new ContentHash(SHA_256, expectedFile.sha256()),
                    Map.of()));
        }
        return new ProjectVersionSnapshot(boundReference, files, Map.of());
    }

    private List<ManifestFile> validateManifest(ProjectManifestResponse manifest) {
        if (manifest == null
                || !projectId.equals(manifest.projectId())
                || !frozenVersionId.equals(manifest.version())
                || manifest.files() == null) {
            throw sourceFailure();
        }
        Map<String, ManifestFile> unique = new HashMap<>();
        for (ProjectFileEntry file : manifest.files()) {
            if (file == null || !portablePath(file.path()) || file.sizeBytes() < 0
                    || file.sha256() == null || !file.sha256().matches(LOWER_SHA_256)) {
                throw sourceFailure();
            }
            ManifestFile value = new ManifestFile(file.path(), file.sizeBytes(), file.sha256());
            if (unique.put(file.path(), value) != null) {
                throw sourceFailure();
            }
        }
        List<ManifestFile> result = unique.values().stream()
                .sorted(Comparator.comparing(ManifestFile::path))
                .toList();
        String derivedVersion = ProjectManifestIdentity.derive(result.stream()
                .map(file -> new ProjectManifestIdentity.Entry(
                        new ProjectRelativePath(file.path()),
                        new FileHash(file.sha256()),
                        file.sizeBytes()))
                .toList()).value();
        if (!frozenVersionId.equals(derivedVersion)) {
            throw sourceFailure();
        }
        return result;
    }

    private void validateMaterialization(
            SandboxWorkspaceMaterialization materialized,
            List<ManifestFile> expected
    ) {
        if (materialized == null || materialized.snapshot() == null || materialized.textFiles() == null
                || materialized.snapshot().workspace() == null
                || materialized.snapshot().workspace().projectId() != projectId
                || materialized.snapshot().workspace().projectVersion() == null
                || !frozenVersionId.equals(materialized.snapshot().workspace().projectVersion().value())
                || materialized.snapshot().files() == null) {
            throw sourceFailure();
        }

        Map<String, ManifestFile> expectedByPath = new HashMap<>();
        expected.forEach(file -> expectedByPath.put(file.path(), file));
        Map<String, SandboxFileSnapshot> actualByPath = new HashMap<>();
        for (SandboxFileSnapshot file : materialized.snapshot().files()) {
            if (file == null || file.relativePath() == null || file.fileHash() == null
                    || file.sizeBytes() < 0
                    || actualByPath.put(file.relativePath().value(), file) != null) {
                throw sourceFailure();
            }
        }
        if (!actualByPath.keySet().equals(expectedByPath.keySet())
                || !materialized.textFiles().keySet().equals(expectedByPath.keySet())) {
            throw sourceFailure();
        }
        for (ManifestFile expectedFile : expected) {
            SandboxFileSnapshot actual = actualByPath.get(expectedFile.path());
            if (actual.sizeBytes() != expectedFile.sizeBytes()
                    || !expectedFile.sha256().equals(actual.fileHash().sha256())) {
                throw sourceFailure();
            }
        }
    }

    private static boolean portablePath(String path) {
        if (path == null || path.isBlank() || !path.equals(path.trim())
                || path.startsWith("/") || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*") || path.contains("\\")
                || path.endsWith("/") || path.contains("//")
                || path.chars().anyMatch(character -> character >= 0 && character < 32)) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static WorkspaceException sourceFailure() {
        return new WorkspaceException(WorkspaceErrorCode.SOURCE_FAILURE, OPERATION);
    }

    private record ManifestFile(String path, long sizeBytes, String sha256) {
    }
}
