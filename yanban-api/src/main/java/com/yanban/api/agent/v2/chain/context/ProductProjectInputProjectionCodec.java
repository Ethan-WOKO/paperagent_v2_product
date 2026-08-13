package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.ChainIdentity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure stable roots and exact body references for Project input Context. */
final class ProductProjectInputProjectionCodec {
    static final String VERSION = "product-project-input-v1";
    static final String PAGINATION = "project-input-body-cursor-v1";
    static final String MANIFEST_AUTHORITY = "PROJECT_MANIFEST";
    static final String FILE_AUTHORITY = "PROJECT_FILE";
    static final String CANDIDATE_AUTHORITY = "CANDIDATE_FILE";

    private ProductProjectInputProjectionCodec() {
    }

    static List<ProjectFileEntry> ordered(ProjectManifestResponse manifest) {
        return manifest.files().stream().sorted(Comparator
                .comparing((ProjectFileEntry value) ->
                        ProjectMaterialScope.normalize(value.path()))
                .thenComparing(ProjectFileEntry::path)).toList();
    }

    static ChainContextValue manifestRoot(ProjectManifestResponse manifest) {
        List<ProjectFileEntry> files = ordered(manifest);
        Map<String, List<ProjectFileEntry>> partitions = new TreeMap<>();
        for (ProjectFileEntry file : files) {
            String path = ProjectMaterialScope.normalize(file.path());
            int separator = path.indexOf('/');
            String partition = separator < 0 ? "."
                    : path.substring(0, separator);
            partitions.computeIfAbsent(partition, ignored ->
                    new ArrayList<>()).add(file);
        }
        List<ChainContextValue> roots = partitions.entrySet().stream()
                .map(entry -> partition(entry.getKey(), entry.getValue()))
                .map(value -> (ChainContextValue) value).toList();
        String ref = manifestRef(manifest.projectId());
        return ChainContextValue.object(Map.of(
                "projectId", ChainContextValue.number(manifest.projectId()),
                "projectVersion", referenced(manifest.version(), ref),
                "manifestFingerprint", referenced(manifest.version(), ref),
                "fileCount", ChainContextValue.number(files.size()),
                "totalBytes", ChainContextValue.number(files.stream()
                        .mapToLong(ProjectFileEntry::sizeBytes).sum()),
                "partitions", ChainContextValue.array(roots),
                "complete", ChainContextValue.bool(true),
                "bodyAuthority", bodyAuthority(MANIFEST_AUTHORITY, ref,
                        manifest.version(), manifestBodyDigest(manifest))));
    }

    static ChainContextValue projectFiles(
            ProjectManifestResponse manifest, List<String> paths) {
        Map<String, ProjectFileEntry> byPath = new TreeMap<>(
                Comparator.comparing(ProjectMaterialScope::normalize));
        ordered(manifest).forEach(file -> byPath.put(file.path(), file));
        List<ChainContextValue> values = paths.stream()
                .sorted(Comparator.comparing(ProjectMaterialScope::normalize))
                .map(path -> file(byPath.get(path), manifest))
                .map(value -> (ChainContextValue) value).toList();
        return ChainContextValue.array(values);
    }

    static ChainContextValue candidateFiles(CandidateArtifactResponse candidate) {
        if (candidate == null) return ChainContextValue.array(List.of());
        List<ChainContextValue> values = candidate.reviewDiff().entries().stream()
                .sorted(Comparator.comparing(value ->
                        ProjectMaterialScope.normalize(
                                value.relativePath().value())))
                .map(value -> candidateFile(candidate, value))
                .map(value -> (ChainContextValue) value).toList();
        return ChainContextValue.array(values);
    }

    static ChainContextValue explicitVector(
            ProjectManifestResponse manifest, List<String> paths) {
        return projectFiles(manifest, paths);
    }

    static String candidateDiffDigest(CandidateArtifactResponse candidate) {
        StringBuilder canonical = new StringBuilder(
                candidate.projectVersion().value());
        candidate.reviewDiff().entries().stream()
                .sorted(Comparator.comparing(value ->
                        value.relativePath().value()))
                .forEach(value -> canonical.append('\0').append(value.type())
                        .append('\0').append(value.relativePath().value())
                        .append('\0').append(value.baseFileHash() == null
                                ? ChainIdentity.NONE
                                : value.baseFileHash().sha256())
                        .append('\0').append(value.resultFileHash() == null
                                ? ChainIdentity.NONE
                                : value.resultFileHash().sha256()));
        return ProductChainContractProjectionCodec.sha256(
                canonical.toString());
    }

    static String manifestBodyDigest(ProjectManifestResponse manifest) {
        ChainContextValue bodySet = ChainContextValue.array(ordered(manifest)
                .stream().map(ProductProjectInputProjectionCodec
                        ::manifestEntryValue).toList());
        return ProductChainContractProjectionCodec.sha256(
                ProductChainContractProjectionCodec.canonicalJson(bodySet));
    }

    static String manifestEntryBody(ProjectFileEntry file) {
        return ProductChainContractProjectionCodec.canonicalJson(
                manifestEntryValue(file));
    }

    private static ChainContextValue manifestEntryValue(ProjectFileEntry file) {
        return ChainContextValue.object(Map.of(
                "path", ChainContextValue.text(file.path()),
                "sizeBytes", ChainContextValue.number(file.sizeBytes()),
                "sha256", ChainContextValue.text(file.sha256())));
    }

    static String manifestRef(long projectId) {
        return "project-manifest:" + projectId;
    }

    static String fileRef(long projectId, String path) {
        return "project-file:" + projectId + ":" + encode(path);
    }

    static String candidateRef(long artifactId, String path) {
        return "candidate-file:" + artifactId + ":" + encode(path);
    }

    static String decodePath(String ref, String prefix) {
        if (ref == null || !ref.startsWith(prefix)) {
            throw new IllegalArgumentException("body authority ref is invalid");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(
                    ref.substring(prefix.length())), StandardCharsets.UTF_8);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "body authority path is invalid", invalid);
        }
    }

    private static ChainContextValue.ObjectValue partition(
            String name, List<ProjectFileEntry> files) {
        String canonical = files.stream().map(file ->
                        ProjectMaterialScope.normalize(file.path()) + "\0"
                                + file.path() + "\0" + file.sizeBytes()
                                + "\0" + file.sha256())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return ChainContextValue.object(Map.of(
                "name", ChainContextValue.text(name),
                "fileCount", ChainContextValue.number(files.size()),
                "sizeBytes", ChainContextValue.number(files.stream()
                        .mapToLong(ProjectFileEntry::sizeBytes).sum()),
                "digest", ChainContextValue.text(
                        ProductChainContractProjectionCodec.sha256(canonical))));
    }

    private static ChainContextValue.ObjectValue file(
            ProjectFileEntry file, ProjectManifestResponse manifest) {
        if (file == null) {
            throw new IllegalArgumentException("resolved Project file is missing");
        }
        String ref = fileRef(manifest.projectId(), file.path());
        return ChainContextValue.object(Map.of(
                "path", ChainContextValue.text(file.path()),
                "sizeBytes", ChainContextValue.number(file.sizeBytes()),
                "sha256", referenced(file.sha256(), ref),
                "bodyAuthority", bodyAuthority(FILE_AUTHORITY, ref,
                        manifest.version(), file.sha256())));
    }

    private static ChainContextValue.ObjectValue candidateFile(
            CandidateArtifactResponse candidate,
            CandidateReviewDiff.Entry entry) {
        String path = entry.relativePath().value();
        String ref = candidateRef(candidate.artifactId(), path);
        String digest = entry.resultFileHash() == null ? null
                : entry.resultFileHash().sha256();
        return ChainContextValue.object(Map.of(
                "path", ChainContextValue.text(path),
                "changeType", ChainContextValue.text(entry.type().name()),
                "baseSha256", entry.baseFileHash() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.text(entry.baseFileHash().sha256()),
                "resultSha256", digest == null ? ChainContextValue.nil()
                        : referenced(digest, ref),
                "effectiveSha256", digest == null ? ChainContextValue.nil()
                        : referenced(digest, ref),
                "bodyAuthority", digest == null ? ChainContextValue.nil()
                        : bodyAuthority(CANDIDATE_AUTHORITY, ref,
                                candidate.fingerprint().sha256(), digest)));
    }

    private static ChainContextValue.ObjectValue bodyAuthority(
            String type, String ref, String version, String digest) {
        return ChainContextValue.object(Map.of(
                "authorityType", ChainContextValue.text(type),
                "authorityRef", referenced(ref, ref),
                "authorityVersion", referenced(version, ref),
                "authorityDigest", referenced(digest, ref),
                "paginationVersion", ChainContextValue.text(PAGINATION)));
    }

    private static ChainContextValue.Text referenced(
            String value, String ref) {
        return ChainContextValue.referencedText(value, ref);
    }

    private static String encode(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                path.getBytes(StandardCharsets.UTF_8));
    }
}
