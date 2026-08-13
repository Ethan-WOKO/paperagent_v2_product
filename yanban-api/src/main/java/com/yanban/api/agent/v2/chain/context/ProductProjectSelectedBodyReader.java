package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads complete text only for already selected exact Project file refs. */
final class ProductProjectSelectedBodyReader {
    private ProductProjectSelectedBodyReader() {
    }

    static Map<String, String> read(
            ProjectService projects,
            ChainPersistenceRecords.TaskRecord task,
            ProjectManifestResponse manifest,
            List<String> paths) {
        Map<String, ProjectFileEntry> entries = new LinkedHashMap<>();
        manifest.files().forEach(value -> entries.put(value.path(), value));
        Map<String, String> result = new LinkedHashMap<>();
        for (String path : paths) {
            ProjectFileEntry expected = entries.get(path);
            var actual = projects.readFile(
                    task.userId(), task.projectId(), path);
            if (expected == null || !actual.path().equals(expected.path())
                    || actual.sizeBytes() != expected.sizeBytes()
                    || !actual.sha256().equals(expected.sha256())
                    || !ProductChainContractProjectionCodec.sha256(
                    actual.content()).equals(expected.sha256())) {
                throw ProductChainContextProjectionSupport.blocked(
                        ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                        "Project file body digest does not match manifest");
            }
            result.put(path, actual.content());
        }
        return Map.copyOf(result);
    }
}
