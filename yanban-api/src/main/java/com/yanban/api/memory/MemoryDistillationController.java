package com.yanban.api.memory;

import com.yanban.api.security.JwtUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/memory/distillation")
public class MemoryDistillationController {
    private final MemoryDistillationService service;

    MemoryDistillationController(MemoryDistillationService service) {
        this.service = service;
    }

    @GetMapping
    public MemoryDistillationSettingsResponse settings(@AuthenticationPrincipal JwtUser currentUser) {
        return service.getSettings(currentUser.id());
    }

    @PutMapping
    public MemoryDistillationSettingsResponse updateSettings(
            @AuthenticationPrincipal JwtUser currentUser,
            @Valid @RequestBody UpdateMemoryDistillationSettingsRequest request) {
        return service.updateSettings(currentUser.id(), request.autoEnabled());
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MemoryDistillationJobResponse start(@AuthenticationPrincipal JwtUser currentUser) {
        return service.requestManual(currentUser.id());
    }

    @GetMapping("/jobs/{jobId}")
    public MemoryDistillationJobResponse job(@AuthenticationPrincipal JwtUser currentUser,
                                             @PathVariable long jobId) {
        return service.getJob(currentUser.id(), jobId);
    }
}
