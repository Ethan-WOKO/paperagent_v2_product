package com.yanban.knowledge.web;

import com.yanban.knowledge.service.KnowledgeDocumentService;
import com.yanban.knowledge.service.KnowledgeIngestionService;
import com.yanban.knowledge.service.KnowledgeSearchOptions;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import com.yanban.knowledge.service.KnowledgeUploadService;
import com.yanban.core.user.UserAccountPolicy;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;
    private final KnowledgeUploadService uploadService;
    private final KnowledgeDocumentService documentService;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;

    public KnowledgeController(KnowledgeIngestionService ingestionService,
                               KnowledgeSearchService searchService,
                               KnowledgeUploadService uploadService,
                               KnowledgeDocumentService documentService,
                               ObjectProvider<UserAccountPolicy> accountPolicy) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.uploadService = uploadService;
        this.documentService = documentService;
        this.accountPolicy = accountPolicy;
    }

    @PostMapping("/api/v1/kb/documents/simple-upload")
    @ResponseStatus(HttpStatus.CREATED)
    public KbDocumentResponse simpleUpload(@AuthenticationPrincipal(expression = "id") Long userId,
                                           @ModelAttribute SimpleUploadForm form) {
        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null && form.file() != null) {
            policy.assertCanUploadKnowledge(userId, form.file().getSize());
        }
        return KbDocumentResponse.from(ingestionService.ingestSimple(userId, form.file(), form.isPublic()));
    }

    @PostMapping("/api/v1/upload/chunk")
    @ResponseStatus(HttpStatus.CREATED)
    public ChunkUploadResponse uploadChunk(@AuthenticationPrincipal(expression = "id") Long userId,
                                           @ModelAttribute ChunkUploadForm form) {
        return uploadService.uploadChunk(userId, new ChunkUploadRequest(
                form.uploadId(),
                form.filename(),
                form.chunkNumber(),
                form.totalChunks(),
                form.chunkMd5(),
                form.file()
        ));
    }

    @PostMapping("/api/v1/upload/merge")
    @ResponseStatus(HttpStatus.CREATED)
    public KbDocumentResponse mergeUpload(@AuthenticationPrincipal(expression = "id") Long userId,
                                          @Valid @RequestBody MergeUploadRequest request) {
        return uploadService.mergeChunks(userId, request);
    }

    @GetMapping("/api/v1/kb/documents")
    public List<KbDocumentListItemResponse> listDocuments(@AuthenticationPrincipal(expression = "id") Long userId) {
        return documentService.listVisibleDocuments(userId);
    }

    @GetMapping("/api/v1/kb/documents/{documentId}/download")
    public ResponseEntity<Resource> downloadDocument(@AuthenticationPrincipal(expression = "id") Long userId,
                                                      @PathVariable Long documentId) {
        var download = documentService.downloadDocument(userId, documentId);
        // Spring's ResourceHttpMessageConverter closes this stream, including on transfer failure.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.stream()));
    }

    @GetMapping("/api/v1/kb/documents/{documentId}/preview")
    public KbDocumentPreviewResponse previewDocument(@AuthenticationPrincipal(expression = "id") Long userId,
                                                     @PathVariable Long documentId,
                                                     @RequestParam(required = false) Integer maxChars) {
        return documentService.previewOwnedDocument(userId, documentId, maxChars);
    }

    @DeleteMapping("/api/v1/kb/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@AuthenticationPrincipal(expression = "id") Long userId,
                               @PathVariable Long documentId) {
        documentService.deleteOwnedDocument(userId, documentId);
    }

    @PostMapping("/api/v1/search")
    public List<KnowledgeSearchResult> search(@AuthenticationPrincipal(expression = "id") Long userId,
                                              @Valid @RequestBody SearchRequest request) {
        int topK = request.topK() == null ? 5 : request.topK();
        return searchService.search(request.query(), new KnowledgeSearchOptions(
                userId,
                topK,
                request.projectId(),
                Boolean.TRUE.equals(request.includeSuperseded())
        ));
    }

    public record SimpleUploadForm(MultipartFile file, boolean isPublic) {
    }

    public record ChunkUploadForm(String uploadId,
                                  String filename,
                                  Integer chunkNumber,
                                  Integer totalChunks,
                                  String chunkMd5,
                                  MultipartFile file) {
    }
}
