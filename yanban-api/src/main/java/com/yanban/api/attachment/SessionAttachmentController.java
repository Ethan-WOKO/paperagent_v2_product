package com.yanban.api.attachment;

import com.yanban.api.security.JwtUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/agent/sessions/{sessionId}/attachments")
public class SessionAttachmentController {
    private final SessionAttachmentService service;
    public SessionAttachmentController(SessionAttachmentService service) { this.service=service; }
    @GetMapping
    public List<SessionAttachment.View> list(@AuthenticationPrincipal JwtUser user,@PathVariable Long sessionId) {
        return service.list(user.id(),sessionId);
    }
    @PostMapping(consumes="multipart/form-data")
    public SessionAttachment.View upload(@AuthenticationPrincipal JwtUser user,@PathVariable Long sessionId,@RequestParam MultipartFile file) {
        return service.upload(user.id(),sessionId,file);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal JwtUser user,@PathVariable Long sessionId,@PathVariable Long id) {
        service.remove(user.id(),sessionId,id);
    }
    @PostMapping("/{id}/knowledge")
    public java.util.Map<String,Long> promote(@AuthenticationPrincipal JwtUser user,@PathVariable Long sessionId,@PathVariable Long id) {
        return java.util.Map.of("documentId",service.promote(user.id(),sessionId,id));
    }
}
