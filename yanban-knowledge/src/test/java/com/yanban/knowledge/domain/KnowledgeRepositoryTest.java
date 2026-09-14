package com.yanban.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = KnowledgeRepositoryTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class KnowledgeRepositoryTest {

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbChunkUploadRepository uploads;

    @Autowired
    KnowledgeRepositoryTest(KbDocumentRepository documents,
                            KbChunkRepository chunks,
                            KbChunkUploadRepository uploads) {
        this.documents = documents;
        this.chunks = chunks;
        this.uploads = uploads;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {KbDocument.class, KbChunk.class, KbChunkUpload.class})
    @EnableJpaRepositories(basePackageClasses = {KbDocumentRepository.class, KbChunkRepository.class, KbChunkUploadRepository.class})
    static class TestConfig {
    }

    @Test
    void insertKbDocumentChunkAndChunkUpload() {
        KbDocument document = documents.save(new KbDocument(3001L, "notes.pdf", "PROCESSING", false));
        document.setObjectKey("kb/notes.pdf");
        document.setMimeType("application/pdf");
        document.setFileSize(1024L);
        document.setErrorMessage(null);
        document.setProjectId(9001L);
        document.setLineageId("paper-task-1");
        document.setVersionNo(2);
        document.setVersionStatus("ACTIVE");
        document.setSourceTaskType("PAPER_POLISH");
        document.setSourceTaskId(7001L);
        document.setSourceArtifactId(8001L);
        document.setSourceDocumentId(6001L);
        document.setCanonicalKey("doi:10.0000/example");
        documents.save(document);

        chunks.save(new KbChunk(document.getId(), 0, "第一段内容"));
        uploads.save(new KbChunkUpload(3001L, "upload-1", "notes.pdf", 1, 2, 512L, "md5", "tmp/upload-1/1", "UPLOADED"));

        KbDocument saved = documents.findByIdAndUserId(document.getId(), 3001L).orElseThrow();
        assertThat(saved.getProjectId()).isEqualTo(9001L);
        assertThat(saved.getLineageId()).isEqualTo("paper-task-1");
        assertThat(saved.getVersionNo()).isEqualTo(2);
        assertThat(saved.getVersionStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getSourceTaskType()).isEqualTo("PAPER_POLISH");
        assertThat(saved.getSourceTaskId()).isEqualTo(7001L);
        assertThat(saved.getSourceArtifactId()).isEqualTo(8001L);
        assertThat(saved.getSourceDocumentId()).isEqualTo(6001L);
        assertThat(saved.getCanonicalKey()).isEqualTo("doi:10.0000/example");
        assertThat(saved.getEffectiveAt()).isNotNull();
        assertThat(chunks.findByDocumentIdOrderByChunkIndexAsc(document.getId())).hasSize(1);
        assertThat(uploads.findByUploadIdOrderByChunkNumberAsc("upload-1")).hasSize(1);
    }

    @Test
    void newKbDocumentKeepsVersionDefaults() {
        KbDocument document = documents.saveAndFlush(new KbDocument(3002L, "legacy.md", "READY", false));

        assertThat(document.getSourceType()).isEqualTo("USER_UPLOAD");
        assertThat(document.getVersionNo()).isEqualTo(1);
        assertThat(document.getVersionStatus()).isEqualTo("ACTIVE");
        assertThat(document.getProjectId()).isNull();
        assertThat(document.getLineageId()).isNull();
        assertThat(document.getCanonicalKey()).isNull();
        assertThat(document.getEffectiveAt()).isNotNull();
    }

    @Test
    void publicFileCandidatesExcludeOtherPublishersPrivateAndInactiveDocuments() {
        KbDocument shared = documents.save(new KbDocument(9L, "public.md", "READY", true));
        documents.save(new KbDocument(8L, "ordinary-public.md", "READY", true));
        documents.save(new KbDocument(9L, "private.md", "READY", false));
        documents.save(new KbDocument(9L, "processing.md", "PROCESSING", true));
        for (String state : java.util.List.of("SUPERSEDED", "ARCHIVED", "DELETED")) {
            KbDocument retired = new KbDocument(9L, state + ".md", "READY", true);
            retired.setVersionStatus(state);
            documents.save(retired);
        }
        KbDocument deleted = new KbDocument(9L, "deleted.md", "READY", true);
        deleted.setDeletedAt(java.time.Instant.now());
        documents.save(deleted);
        documents.flush();
        assertThat(documents.findPublicDocumentsByPublisherIds(java.util.Set.of(9L)))
                .extracting(KbDocument::getId).containsExactly(shared.getId());
    }
}
