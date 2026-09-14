package com.yanban.knowledge.service;

import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.*;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeDocumentServiceTest {
    final KbDocumentRepository documents = mock(KbDocumentRepository.class);
    final KbChunkRepository chunks = mock(KbChunkRepository.class);
    final KnowledgeIndexService index = mock(KnowledgeIndexService.class);
    final MinioClient minio = mock(MinioClient.class);
    final KnowledgeDocumentPublisherPolicy publishers = mock(KnowledgeDocumentPublisherPolicy.class);
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    final KnowledgeStorageProperties storage = new KnowledgeStorageProperties();

    KnowledgeDocumentService service() {
        return new KnowledgeDocumentService(documents, chunks, index, minio, storage,
                beans.getBeanProvider(UserAccountPolicy.class), beans.getBeanProvider(KnowledgeDocumentPublisherPolicy.class));
    }

    KbDocument doc(long id, long owner, boolean shared) {
        KbDocument doc = new KbDocument(owner, "说明 文档.pdf", "READY", shared);
        ReflectionTestUtils.setField(doc, "id", id);
        ReflectionTestUtils.setField(doc, "updatedAt", Instant.parse("2026-09-14T00:00:00Z"));
        doc.setObjectKey("kb/original-" + id);
        when(documents.findById(id)).thenReturn(Optional.of(doc));
        return doc;
    }

    void administrator(long id) {
        beans.addBean("publishers", publishers);
        when(publishers.administratorIds()).thenReturn(Set.of(id));
        when(publishers.isAdministrator(id)).thenReturn(true);
    }

    void denied(long viewer, long id) {
        assertThatThrownBy(() -> service().downloadDocument(viewer, id))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                    assertThat(ex.getReason()).doesNotContain("kb/");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "FAILED", "PROCESSING", "RETRYING"})
    void ownerGetsExactOriginalRegardlessOfParsingStatus(String status) throws Exception {
        KbDocument doc = doc(1L, 7L, false);
        doc.setStatus(status);
        doc.setVersionStatus("SUPERSEDED");
        byte[] bytes = {0, 1, (byte) 255, 13, 10};
        ByteArrayInputStream input = spy(new ByteArrayInputStream(bytes));
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "yanban-agent", "", "kb/original-1", input));
        try (var download = service().downloadDocument(7L, 1L)) {
            assertThat(download.stream().readAllBytes()).isEqualTo(bytes);
            assertThat(download.filename()).isEqualTo("说明 文档.pdf");
        }
        verify(input).close();
    }

    @Test
    void ordinaryPublicAndPrivateAndAdministratorPrivateAreNotShared() {
        administrator(9L);
        doc(1, 8, true);
        doc(2, 8, false);
        doc(3, 9, false);
        denied(7, 1); denied(7, 2); denied(7, 3); denied(9, 2);
        verifyNoInteractions(minio);
    }

    @Test
    void administratorPublicDownloadRechecksRoleAndVisibility() throws Exception {
        administrator(9L);
        KbDocument doc = doc(1, 9, true);
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "yanban-agent", "", "x", new ByteArrayInputStream(new byte[]{42})));
        try (var download = service().downloadDocument(7L, 1L)) {
            assertThat(download.stream().read()).isEqualTo(42);
        }
        when(publishers.isAdministrator(9L)).thenReturn(false);
        denied(7, 1);
        when(publishers.isAdministrator(9L)).thenReturn(true);
        ReflectionTestUtils.setField(doc, "isPublic", false);
        denied(7, 1);
        verify(minio, times(1)).getObject(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETED", "ARCHIVED"})
    void retiredDocumentCannotBeDownloadedEvenByOwner(String version) {
        KbDocument doc = doc(1, 7, true);
        doc.setVersionStatus(version);
        denied(7, 1);
        verifyNoInteractions(minio);
    }

    @Test
    void missingDeletedAndNoPublisherFailBeforeStorage() {
        KbDocument doc = doc(1, 7, false);
        doc.setObjectKey(null);
        denied(7, 1);
        doc.setObjectKey("kb/original");
        doc.setDeletedAt(Instant.now());
        denied(7, 1);
        doc(2, 9, true);
        denied(7, 2);
        denied(7, 999);
        assertThatThrownBy(() -> service().downloadDocument(null, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(minio);
    }

    @Test
    void sharedDocumentMustRemainReadyAndActive() {
        administrator(9L);
        KbDocument doc = doc(1, 9, true);
        doc.setStatus("FAILED"); denied(7, 1);
        doc.setStatus("READY"); doc.setVersionStatus("SUPERSEDED"); denied(7, 1);
        verifyNoInteractions(minio);
    }

    @Test
    void missingObjectAndInfrastructureFailureAreSanitized() throws Exception {
        doc(1, 7, false);
        ErrorResponse error = mock(ErrorResponse.class);
        when(error.code()).thenReturn("NoSuchKey");
        var missing = new ErrorResponseException(error, null, "secret-object-key");
        when(minio.getObject(any())).thenThrow(missing);
        denied(7, 1);
        doThrow(new IOException("secret-endpoint")).when(minio).getObject(any());
        assertThatThrownBy(() -> service().downloadDocument(7L, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getMessage()).doesNotContain("secret");
                    assertThat(ex.getCause()).isNull();
                });
    }

    @Test
    void listMergesDeduplicatesAndSortsWithoutStorageCalls() {
        administrator(9L);
        KbDocument own = doc(1, 7, false);
        KbDocument shared = doc(2, 9, true);
        shared.setErrorMessage("private parser details");
        KbDocument legacy = doc(3, 7, true);
        legacy.setObjectKey(null);
        when(documents.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(own, legacy));
        when(documents.findPublicDocumentsByPublisherIds(Set.of(9L))).thenReturn(List.of(shared));
        var listed = service().listVisibleDocuments(7L);
        assertThat(listed).extracting(item -> item.id()).containsExactly(3L, 2L, 1L);
        assertThat(listed.get(0).downloadAvailable()).isFalse();
        assertThat(listed.get(1).administratorPublic()).isTrue();
        assertThat(listed.get(1).ownedByCurrentUser()).isFalse();
        assertThat(listed.get(1).errorMessage()).isNull();
        when(documents.findByUserIdOrderByCreatedAtDesc(9L)).thenReturn(List.of(shared));
        assertThat(service().listVisibleDocuments(9L)).hasSize(1).allMatch(item -> item.ownedByCurrentUser());
        verifyNoInteractions(minio);
    }

    @Test
    void emptyPublishersKeepOwnerListAndSkipInQuery() {
        KbDocument own = doc(1, 7, false);
        when(documents.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(own));
        assertThat(service().listVisibleDocuments(7L)).hasSize(1);
        verify(documents, never()).findPublicDocumentsByPublisherIds(any());
    }

    @Test
    void legacyNullVersionKeepsActiveSharingSemantics() throws Exception {
        administrator(9L);
        KbDocument shared = doc(1, 9, true);
        ReflectionTestUtils.setField(shared, "versionStatus", null);
        when(documents.findPublicDocumentsByPublisherIds(Set.of(9L))).thenReturn(List.of(shared));
        assertThat(service().listVisibleDocuments(7L)).hasSize(1).allMatch(item -> item.downloadAvailable());
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "bucket", "", "key", new ByteArrayInputStream(new byte[]{42})));
        try (var download = service().downloadDocument(7L, 1L)) {
            assertThat(download.stream().read()).isEqualTo(42);
        }
    }
}
