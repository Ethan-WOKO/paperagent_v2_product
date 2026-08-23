package com.yanban.knowledge.web;

import com.yanban.knowledge.service.KnowledgeDeadLetterService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/knowledge/dead-letters")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeDeadLetterController {
    private final KnowledgeDeadLetterService service;
    public KnowledgeDeadLetterController(KnowledgeDeadLetterService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> pending() {
        return service.pending().stream().map(value -> Map.<String, Object>of(
                "id", value.getId(), "documentId", value.getDocumentId(),
                "eventId", value.getOriginalEventId(), "errorType", value.getErrorType() == null ? "" : value.getErrorType(),
                "errorMessage", value.getErrorMessage() == null ? "" : value.getErrorMessage(),
                "retryCount", value.getRetryCount(), "createdAt", value.getCreatedAt())).toList();
    }

    @PostMapping("/{id}/redrive")
    public Map<String, String> redrive(@PathVariable long id) {
        return Map.of("eventId", service.redrive(id));
    }
}
