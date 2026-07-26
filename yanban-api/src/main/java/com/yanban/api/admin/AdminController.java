package com.yanban.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<AdminUserSummaryResponse> users() {
        return service.listUsers();
    }

    @GetMapping("/users/{userId}")
    public AdminUserDetailResponse user(@PathVariable Long userId) {
        return service.userDetail(userId);
    }

    @PutMapping("/users/{userId}/quota")
    public AdminUserSummaryResponse updateQuota(@PathVariable Long userId,
                                                @Valid @RequestBody AdminQuotaUpdateRequest request) {
        return service.updateQuota(userId, request);
    }

    @PostMapping("/users/{userId}/quota/reset")
    public AdminUserSummaryResponse resetQuota(@PathVariable Long userId) {
        return service.resetQuotaUsage(userId);
    }

    @GetMapping("/invite-codes")
    public List<AdminInviteCodeResponse> inviteCodes() {
        return service.listInviteCodes();
    }

    @DeleteMapping("/demo/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDemoMessage(@PathVariable Long messageId) {
        service.deleteDemoMessage(messageId);
    }

    @DeleteMapping("/demo/chats")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDemoChats() {
        service.clearDemoChats();
    }

    @DeleteMapping("/demo/projects")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDemoProjects() {
        service.clearDemoProjects();
    }
}
