package com.yanban.api.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.error.ApiSecurityErrorWriter;
import com.yanban.api.security.*;
import com.yanban.api.user.*;
import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.*;
import com.yanban.knowledge.service.*;
import com.yanban.knowledge.web.KnowledgeController;
import io.minio.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC, security filter, JWT parsing, role bridge, document and search services; mocked persistence/storage. */
@SpringJUnitConfig(KnowledgeDocumentDownloadControllerTest.Config.class)
@WebAppConfiguration
class KnowledgeDocumentDownloadControllerTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({KnowledgeController.class, KnowledgeDocumentService.class, KnowledgeDocumentPublisherPolicyAdapter.class,
            SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrorWriter.class,
            com.yanban.api.error.ApiExceptionHandler.class})
    static class Config {
        @Bean SysUserRepository users() { return mock(SysUserRepository.class); }
        @Bean KbDocumentRepository documents() { return mock(KbDocumentRepository.class); }
        @Bean KbChunkRepository chunks() { return mock(KbChunkRepository.class); }
        @Bean KnowledgeIndexService index() { return mock(KnowledgeIndexService.class); }
        @Bean KnowledgeIngestionService ingestion() { return mock(KnowledgeIngestionService.class); }
        @Bean KnowledgeUploadService upload() { return mock(KnowledgeUploadService.class); }
        @Bean UserAccountPolicy accountPolicy() { return mock(UserAccountPolicy.class); }
        @Bean MinioClient minio() { return mock(MinioClient.class); }
        @Bean KnowledgeStorageProperties storage() { return new KnowledgeStorageProperties(); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean KnowledgeSearchService search(KbChunkRepository chunks, KbDocumentRepository documents) {
            return new SimpleKnowledgeSearchService(chunks, documents);
        }
        @Bean JwtService jwt() {
            JwtProperties properties = new JwtProperties();
            properties.setSecret("knowledge_download_test_secret_at_least_32_chars");
            return new JwtService(properties);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired SysUserRepository users;
    @Autowired KbDocumentRepository documents;
    @Autowired KbChunkRepository chunks;
    @Autowired MinioClient minio;
    @Autowired JwtService jwt;
    @Autowired UserAccountPolicy accountPolicy;
    @Autowired KnowledgeController controller;
    MockMvc mvc;
    Map<Long, SysUser> identities;
    Map<Long, KbDocument> docs;

    @BeforeEach
    void setUp() {
        reset(users, documents, chunks, minio, accountPolicy);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        identities = new HashMap<>();
        docs = new HashMap<>();
        for (long id : List.of(7L, 8L, 9L, 10L)) {
            SysUser user = new SysUser("download-user-" + id, "unused");
            ReflectionTestUtils.setField(user, "id", id);
            if (id == 9) user.setRole("ADMIN");
            if (id == 10) user.setAccountType("DEMO");
            identities.put(id, user);
        }
        when(users.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(identities.get(call.getArgument(0))));
        when(users.findByIdAndDeletedAtIsNull(anyLong())).thenAnswer(call ->
                Optional.ofNullable(identities.get(call.getArgument(0))).filter(user -> !user.isDeleted()));
        when(users.findActiveAdministratorIds()).thenAnswer(call -> identities.values().stream()
                .filter(user -> user.isAdmin() && !user.isDeleted()).map(SysUser::getId).collect(java.util.stream.Collectors.toSet()));
        when(documents.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(docs.get(call.getArgument(0))));
        when(documents.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(call ->
                Optional.ofNullable(docs.get(call.getArgument(0))).filter(doc -> doc.getUserId().equals(call.getArgument(1))));
        when(documents.findByUserIdOrderByCreatedAtDesc(anyLong())).thenAnswer(call -> docs.values().stream()
                .filter(doc -> doc.getUserId().equals(call.getArgument(0))).toList());
        when(documents.findPublicDocumentsByPublisherIds(any())).thenAnswer(call -> {
            Set<Long> ids = call.getArgument(0);
            return docs.values().stream().filter(doc -> ids.contains(doc.getUserId()) && Boolean.TRUE.equals(doc.getIsPublic())).toList();
        });
    }

    String token(long id) { return "Bearer " + jwt.createAccessToken(identities.get(id)); }

    KbDocument doc(long id, long owner, boolean shared, String name) {
        KbDocument doc = new KbDocument(owner, name, "READY", shared);
        ReflectionTestUtils.setField(doc, "id", id);
        ReflectionTestUtils.setField(doc, "updatedAt", Instant.parse("2026-09-14T00:00:00Z"));
        doc.setObjectKey("private-bucket-key/" + id);
        docs.put(id, doc);
        return doc;
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 9, 10})
    void memberAdministratorAndDemoDownloadAdministratorPublicOriginal(long viewer) throws Exception {
        doc(1, 9, true, "说明 文档.pdf");
        byte[] original = {0, 1, (byte) 255, 13, 10};
        var input = spy(new ByteArrayInputStream(original));
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "bucket", "", "key", input));
        mvc.perform(get("/api/v1/kb/documents").header("Authorization", token(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].administratorPublic").value(true))
                .andExpect(jsonPath("$[0].ownedByCurrentUser").value(viewer == 9))
                .andExpect(jsonPath("$[0].downloadAvailable").value(true))
                .andExpect(jsonPath("$[0].objectKey").doesNotExist());
        var response = mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", token(viewer)))
                .andExpect(status().isOk()).andExpect(content().bytes(original))
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();
        assertThat(ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename()).isEqualTo("说明 文档.pdf");
        verify(input).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerDownloadsPrivateOrPublicOriginal(boolean shared) throws Exception {
        doc(1, 7, shared, "my.pdf").setStatus("FAILED");
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "bucket", "", "key", new ByteArrayInputStream(new byte[]{42})));
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", token(7)))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{42}));
    }

    @Test
    void otherPublicAndPrivateFilesStayOutOfListAndDownloadIncludingAdminViewer() throws Exception {
        doc(1, 8, true, "ordinary-public.md"); doc(2, 8, false, "private.md"); doc(3, 9, false, "admin-private.md");
        mvc.perform(get("/api/v1/kb/documents").header("Authorization", token(7)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        for (long id : List.of(1L, 2L, 3L, 999L)) {
            mvc.perform(get("/api/v1/kb/documents/{id}/download", id).header("Authorization", token(7))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/kb/documents/2/download").header("Authorization", token(9))).andExpect(status().isNotFound());
        verifyNoInteractions(minio);
    }

    @Test
    void anonymousAndDeletedLoginAreRejectedBeforeDocumentAccess() throws Exception {
        mvc.perform(get("/api/v1/kb/documents/1/download")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/kb/documents")).andExpect(status().isUnauthorized());
        String stale = token(7);
        identities.get(7L).deleteAccount();
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", stale)).andExpect(status().isUnauthorized());
        verifyNoInteractions(documents, minio);
    }

    @Test
    void roleRevocationOrPublisherDeletionRejectsOldPageDownload() throws Exception {
        doc(1, 9, true, "public.md");
        String viewer = token(7);
        identities.get(9L).setRole("USER");
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", viewer)).andExpect(status().isNotFound());
        identities.get(9L).setRole("ADMIN"); identities.get(9L).deleteAccount();
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", viewer)).andExpect(status().isNotFound());
        verifyNoInteractions(minio);
    }

    @Test
    void missingOriginalAndStorageOutageDoNotBecomeDownloads() throws Exception {
        KbDocument doc = doc(1, 7, false, "legacy.md"); doc.setObjectKey(null);
        mvc.perform(get("/api/v1/kb/documents").header("Authorization", token(7)))
                .andExpect(jsonPath("$[0].downloadAvailable").value(false));
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", token(7))).andExpect(status().isNotFound());
        doc.setObjectKey("secret-key");
        when(minio.getObject(any())).thenThrow(new IOException("secret-server"));
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", token(7)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Content-Disposition"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../说明 文档.pdf", "C:\\private\\说明 文档.pdf", "说明\r\n 文档.pdf"})
    void filenameCannotInjectHeadersOrPaths(String name) throws Exception {
        doc(1, 7, false, name);
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "bucket", "", "key", new ByteArrayInputStream(new byte[]{1})));
        var response = mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", token(7)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename()).isEqualTo("说明 文档.pdf");
    }

    @Test
    void ordinaryPublicStillSearchableWhileFileAccessIsDenied() throws Exception {
        doc(1, 8, true, "public.txt");
        KbChunk chunk = new KbChunk(1L, 0, "gamma visible keyword");
        when(chunks.searchAccessibleVersionedChunks(anyString(), eq(7L), isNull(), eq(false), any())).thenReturn(List.of(chunk));
        mvc.perform(post("/api/v1/search").header("Authorization", token(7)).contentType("application/json")
                        .content("{\"query\":\"gamma\",\"topK\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].documentId").value(1))
                .andExpect(jsonPath("$[0].chunkText").value("gamma visible keyword"));
        mvc.perform(get("/api/v1/kb/documents/1/download").header("Authorization", token(7))).andExpect(status().isNotFound());
    }

    @Test
    void sharedFilesDoNotGainPreviewOrDeleteAndOwnerDemoSeedProtectionRemains() throws Exception {
        doc(1, 9, true, "public.md");
        mvc.perform(get("/api/v1/kb/documents/1/preview").header("Authorization", token(7))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/kb/documents/1").header("Authorization", token(7))).andExpect(status().isNotFound());
        doc(2, 10, false, "demo.md").setSourceType("DEMO_SEED");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(accountPolicy).assertCanDeleteKnowledgeDocument(10L, "DEMO_SEED");
        mvc.perform(delete("/api/v1/kb/documents/2").header("Authorization", token(10))).andExpect(status().isForbidden());
        verify(documents, never()).delete(any());
    }

    @Test
    void transferFailureClosesTheControllerResourceStream() throws Exception {
        doc(1, 7, false, "original.pdf");
        InputStream input = spy(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("transfer interrupted"); }
        });
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(Headers.of(), "bucket", "", "key", input));
        var response = controller.downloadDocument(7L, 1L);
        var converter = new org.springframework.http.converter.ResourceHttpMessageConverter();
        assertThatThrownBy(() -> converter.write(response.getBody(), org.springframework.http.MediaType.APPLICATION_OCTET_STREAM,
                new org.springframework.mock.http.MockHttpOutputMessage()))
                .isInstanceOf(IOException.class);
        verify(input).close();
    }
}
