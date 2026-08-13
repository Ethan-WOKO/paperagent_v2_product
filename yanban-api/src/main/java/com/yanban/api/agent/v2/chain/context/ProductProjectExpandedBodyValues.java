package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectManifestResponse;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Adds exact complete bodies only to mechanically selected projections. */
final class ProductProjectExpandedBodyValues {
    private ProductProjectExpandedBodyValues() {
    }

    static ChainContextValue projectFiles(
            ProjectManifestResponse manifest,
            List<String> paths,
            Map<String, String> bodies) {
        List<String> ordered = paths.stream().sorted(Comparator.comparing(
                ProjectMaterialScope::normalize)).toList();
        var identities = (ChainContextValue.ArrayValue)
                ProductProjectInputProjectionCodec.projectFiles(
                        manifest, paths);
        List<ChainContextValue> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            String path = ordered.get(index);
            String body = bodies.get(path);
            if (body == null) throw new IllegalArgumentException(
                    "selected Project body is missing");
            var identity = (ChainContextValue.ObjectValue)
                    identities.values().get(index);
            Map<String, ChainContextValue> value = new TreeMap<>(
                    identity.values());
            String ref = ProductProjectInputProjectionCodec.fileRef(
                    manifest.projectId(), path);
            value.put("body", ChainContextValue.referencedText(body, ref));
            result.add(ChainContextValue.object(value));
        }
        return ChainContextValue.array(result);
    }

    static ChainContextValue candidateFiles(
            CandidateArtifactResponse candidate) {
        if (candidate == null) return ChainContextValue.array(List.of());
        var identities = (ChainContextValue.ArrayValue)
                ProductProjectInputProjectionCodec.candidateFiles(candidate);
        var entries = candidate.reviewDiff().entries().stream().sorted(
                Comparator.comparing(value -> ProjectMaterialScope.normalize(
                        value.relativePath().value()))).toList();
        List<ChainContextValue> result = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var identity = (ChainContextValue.ObjectValue)
                    identities.values().get(index);
            Map<String, ChainContextValue> value = new TreeMap<>(
                    identity.values());
            if (entry.replacementText() == null) {
                value.put("body", ChainContextValue.nil());
            } else {
                String ref = ProductProjectInputProjectionCodec.candidateRef(
                        candidate.artifactId(), entry.relativePath().value());
                value.put("body", ChainContextValue.referencedText(
                        entry.replacementText(), ref));
            }
            result.add(ChainContextValue.object(value));
        }
        return ChainContextValue.array(result);
    }
}
