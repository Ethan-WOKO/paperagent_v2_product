package com.yanban.api.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.skills.SkillDefinition;
import com.yanban.skills.SkillRegistry;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SkillsService {

    private final UserSettingsService userSettingsService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final UserSkillRepository installed;
    private final SkillUploadParser uploads;

    public SkillsService(UserSettingsService userSettingsService,
                         SkillRegistry skillRegistry,
                         ObjectMapper objectMapper, UserSkillRepository installed, SkillUploadParser uploads) {
        this.userSettingsService = userSettingsService;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
        this.installed = installed;
        this.uploads = uploads;
    }

    @Transactional
    public List<SkillListItemResponse> listSkills(Long userId) {
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        Set<String> disabledIds = new HashSet<>(userSettingsService.parseDisabledSkills(settings));
        List<SkillListItemResponse> result = new ArrayList<>(skillRegistry.list().stream()
                .map(skill -> new SkillListItemResponse(
                        skill.id(),
                        skill.name(),
                        skill.description(),
                        skill.builtin(),
                        !disabledIds.contains(skill.id()),
                        skill.path().toString().replace('\\', '/')
                ))
                .toList());
        installed.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream().map(this::item).forEach(result::add);
        return List.copyOf(result);
    }

    @Transactional
    public void setEnabled(Long userId, String skillId, boolean enabled) {
        if (skillId.startsWith("user-")) {
            owned(userId, skillId).setEnabled(enabled);
            return;
        }
        SkillDefinition skill = skillRegistry.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 不存在"));
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        Set<String> disabledIds = new HashSet<>(userSettingsService.parseDisabledSkills(settings));
        if (enabled) {
            disabledIds.remove(skill.id());
        } else {
            disabledIds.add(skill.id());
        }
        settings.update(
                settings.getDefaultProvider(),
                settings.getDeepseekApiKeyEncrypted(),
                settings.getGlmApiKeyEncrypted(),
                settings.getDeepseekModel(),
                settings.getGlmModel(),
                settings.getGithubPatEncrypted(),
                settings.getFilesystemRootsText(),
                writeJson(List.copyOf(disabledIds)),
                settings.getDeepseekTemperature(),
                settings.getMaxSteps(),
                settings.getRagDefaultEnabled(),
                settings.getDeepseekModelsText(),
                settings.getGlmModelsText()
        );
    }

    @Transactional
    public ResolvedSkill resolveEnabledSkill(Long userId, String skillId) {
        if (skillId != null && skillId.startsWith("user-")) {
            UserSkillEntity skill = owned(userId, skillId);
            if (!skill.isEnabled()) throw SkillUploadParser.invalid("Skill 已被禁用，请在设置中启用或重新选择");
            return new ResolvedSkill(skill.getId(), skill.getPrompt(), tools(skill));
        }
        SkillDefinition skill = skillRegistry.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill 不存在: " + skillId));
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        if (userSettingsService.parseDisabledSkills(settings).contains(skillId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill 已被禁用: " + skillId);
        }
        return new ResolvedSkill(skill.id(), skill.prompt(), Set.copyOf(skill.allowedTools()));
    }

    public void refresh() {
        skillRegistry.refresh();
    }

    @Transactional
    public SkillDetailResponse install(Long userId, SkillInstallRequest request) {
        SkillUploadParser.Parsed parsed = uploads.parse(request);
        installed.lockOwner(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String key = SkillUploadParser.nameKey(parsed.name());
        if (key.length() > 100) throw SkillUploadParser.invalid("规范化后的名称不能超过 100 字");
        boolean reserved = skillRegistry.list().stream().anyMatch(skill ->
                SkillUploadParser.nameKey(skill.id()).equals(key) || SkillUploadParser.nameKey(skill.name()).equals(key));
        if (reserved || installed.existsByUserIdAndNameKey(userId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "技能名称已存在或与内置技能冲突，请换一个名称");
        }
        if (installed.countByUserId(userId) >= 100) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "每位用户最多安装 100 个 Skill，请先卸载不需要的技能");
        }
        UserSkillEntity skill = new UserSkillEntity("user-" + UUID.randomUUID(), userId, parsed, writeJson(parsed.allowedTools()));
        installed.saveAndFlush(skill);
        return detail(skill);
    }

    @Transactional
    public SkillDetailResponse detail(Long userId, String skillId) {
        if (skillId.startsWith("user-")) return detail(owned(userId, skillId));
        SkillDefinition skill = skillRegistry.findById(skillId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 不存在或无权访问，请刷新技能列表"));
        boolean enabled = !userSettingsService.parseDisabledSkills(userSettingsService.getOrCreate(userId)).contains(skillId);
        return new SkillDetailResponse(skill.id(), skill.name(), skill.description(), skill.builtin(), enabled,
                false, skill.prompt(), null, Set.copyOf(skill.allowedTools()));
    }

    @Transactional
    public void uninstall(Long userId, String skillId) {
        if (!skillId.startsWith("user-")) {
            if (skillRegistry.findById(skillId).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "内置或服务器预置 Skill 不能卸载，可以禁用");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 不存在或无权访问");
        }
        installed.delete(owned(userId, skillId));
    }

    private UserSkillEntity owned(Long userId, String id) {
        return installed.findByIdAndUserId(id, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 不存在或无权访问，请刷新技能列表"));
    }

    private SkillListItemResponse item(UserSkillEntity skill) {
        return new SkillListItemResponse(skill.getId(), skill.getName(), skill.getDescription(), false, skill.isEnabled(), "");
    }

    private SkillDetailResponse detail(UserSkillEntity skill) {
        return new SkillDetailResponse(skill.getId(), skill.getName(), skill.getDescription(), false,
                skill.isEnabled(), true, skill.getPrompt(), skill.getMetadata(), tools(skill));
    }

    private Set<String> tools(UserSkillEntity skill) {
        try { return Set.copyOf(objectMapper.readValue(skill.getAllowedToolsJson(), new TypeReference<List<String>>() {})); }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "技能工具声明无法读取，请重新安装"); }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化禁用 Skill 列表失败", ex);
        }
    }
}
