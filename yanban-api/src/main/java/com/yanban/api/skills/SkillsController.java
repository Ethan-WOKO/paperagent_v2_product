package com.yanban.api.skills;

import com.yanban.api.security.JwtUser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillsController {

    private final SkillsService skillsService;

    public SkillsController(SkillsService skillsService) {
        this.skillsService = skillsService;
    }

    @GetMapping
    public List<SkillListItemResponse> listSkills(@AuthenticationPrincipal JwtUser currentUser) {
        return skillsService.listSkills(currentUser.id());
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillDetailResponse install(@AuthenticationPrincipal JwtUser currentUser, HttpServletRequest request) throws IOException {
        // Read a hard-bounded raw body before JSON decoding; no multipart file spooling.
        if (request.getContentLengthLong() > SkillUploadParser.REQUEST_BYTES) throw tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(SkillUploadParser.REQUEST_BYTES + 1);
        if (bytes.length > SkillUploadParser.REQUEST_BYTES) throw tooLarge();
        SkillInstallRequest upload;
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
            mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            upload = mapper.readValue(bytes, SkillInstallRequest.class);
        } catch (IOException ex) { throw SkillUploadParser.invalid("安装请求格式无效，请重新选择 SKILL.md 和可选的 skill.yaml 文件"); }
        return skillsService.install(currentUser.id(), upload);
    }

    @GetMapping("/{skillId}")
    public SkillDetailResponse detail(@AuthenticationPrincipal JwtUser currentUser, @PathVariable String skillId) {
        return skillsService.detail(currentUser.id(), skillId);
    }

    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uninstall(@AuthenticationPrincipal JwtUser currentUser, @PathVariable String skillId) {
        skillsService.uninstall(currentUser.id(), skillId);
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "安装请求超过 512 KiB；SKILL.md 上限 64 KiB，skill.yaml 上限 16 KiB");
    }

    @PutMapping("/{skillId}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEnabled(@AuthenticationPrincipal JwtUser currentUser,
                           @PathVariable String skillId,
                           @Valid @RequestBody SkillEnabledRequest request) {
        skillsService.setEnabled(currentUser.id(), skillId, request.enabled());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        skillsService.refresh();
    }
}
