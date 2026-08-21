package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SciFactDatasetTest {

    @TempDir
    Path root;

    @Test
    void loadsCorpusQueriesAndPositiveQrelsIntoDeterministicNestedTiers() throws Exception {
        Files.createDirectories(root.resolve("qrels"));
        Files.writeString(root.resolve("corpus.jsonl"), """
                {"_id":"10","title":"Doc ten","text":"Evidence ten","metadata":{}}
                {"_id":"20","title":"Doc twenty","text":"Evidence twenty","metadata":{}}
                """);
        Files.writeString(root.resolve("queries.jsonl"), """
                {"_id":"q1","text":"first claim","metadata":{}}
                {"_id":"q2","text":"second claim","metadata":{}}
                """);
        Files.writeString(root.resolve("qrels/test.tsv"), """
                query-id\tcorpus-id\tscore
                q1\t10\t1
                q2\t20\t1
                """);
        Files.writeString(root.resolve("qrels/train.tsv"), """
                query-id\tcorpus-id\tscore
                q1\t10\t1
                """);

        SciFactDataset dataset = SciFactDataset.load(root);

        assertThat(dataset.documents()).hasSize(2);
        assertThat(dataset.evaluationCases(1)).hasSize(1);
        assertThat(dataset.evaluationCases(2).subList(0, 1))
                .isEqualTo(dataset.evaluationCases(1));
        assertThat(dataset.evaluationCases(2))
                .allSatisfy(item -> assertThat(item.expectedDocumentIds()).hasSize(1));
        assertThat(dataset.trainingCases()).singleElement()
                .satisfies(item -> assertThat(item.expectedDocumentIds()).containsExactly(10L));
    }
}
