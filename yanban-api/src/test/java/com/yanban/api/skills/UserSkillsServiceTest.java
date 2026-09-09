package com.yanban.api.skills;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.user.SysUser;
import com.yanban.skills.SkillDefinition;
import com.yanban.skills.SkillRegistry;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserSkillsServiceTest {
    private final UserSkillRepository repository = mock(UserSkillRepository.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final SkillRegistry registry = mock(SkillRegistry.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillsService service = new SkillsService(settings, registry, json, repository, new SkillUploadParser());
    private final SkillDefinition builtin = new SkillDefinition("code-review", "code-review", "Builtin", true,
            Path.of("skills/builtin/code-review"), "Builtin prompt", List.of("read_project_file"));

    @BeforeEach void setup() {
        when(registry.list()).thenReturn(List.of(builtin));
        when(registry.findById("code-review")).thenReturn(Optional.of(builtin));
        when(repository.lockOwner(1L)).thenReturn(Optional.of(new SysUser("owner", "hash")));
        when(settings.parseDisabledSkills(any())).thenReturn(List.of());
    }

    @Test void installsOwnedOpaqueRecordAndListsItAlongsideBuiltin() throws Exception {
        SkillDetailResponse created = service.install(1L, request("My review"));
        var capture = org.mockito.ArgumentCaptor.forClass(UserSkillEntity.class);
        verify(repository).saveAndFlush(capture.capture());
        UserSkillEntity row = capture.getValue();
        when(repository.findByUserIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(List.of(row));
        when(repository.findByIdAndUserId(created.id(), 1L)).thenReturn(Optional.of(row));
        assertThat(created.id()).startsWith("user-");
        assertThat(service.listSkills(1L)).extracting(SkillListItemResponse::id).containsExactly("code-review", created.id());
        assertThat(service.resolveEnabledSkill(1L, created.id()).prompt()).isEqualTo("Read carefully");
        assertThat(service.detail(1L, created.id()).allowedTools()).containsExactlyInAnyOrderElementsOf(SkillUploadParser.DEFAULT_TOOLS);
        String listJson = json.writeValueAsString(service.listSkills(1L));
        assertThat(listJson).contains("\"source\":\"builtin\"", "\"managed\":true");
    }

    @Test void crossOwnerCannotListReadResolveToggleOrDelete() {
        String id = "user-foreign";
        assertThat(service.listSkills(2L)).extracting(SkillListItemResponse::id).containsExactly("code-review");
        assertNotFound(() -> service.detail(2L, id));
        assertNotFound(() -> service.resolveEnabledSkill(2L, id));
        assertNotFound(() -> service.setEnabled(2L, id, true));
        assertNotFound(() -> service.uninstall(2L, id));
        verify(repository, never()).delete(any());
    }

    @Test void disabledAndDeletedSelectionsFailWhileEnabledResolvesAgain() {
        var row = new UserSkillEntity("user-test", 1L, new SkillUploadParser().parse(request("Review")), "[]");
        when(repository.findByIdAndUserId("user-test", 1L)).thenReturn(Optional.of(row));
        service.setEnabled(1L, "user-test", false);
        assertThatThrownBy(() -> service.resolveEnabledSkill(1L, "user-test")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("禁用");
        service.setEnabled(1L, "user-test", true);
        assertThat(service.resolveEnabledSkill(1L, "user-test").allowedTools()).isEmpty();
        service.uninstall(1L, "user-test");
        verify(repository).delete(row);
        when(repository.findByIdAndUserId("user-test", 1L)).thenReturn(Optional.empty());
        assertNotFound(() -> service.resolveEnabledSkill(1L, "user-test"));
    }

    @Test void rejectsDuplicatesReservedNamesAndQuotaWithoutWriting() {
        assertThatThrownBy(() -> service.install(1L, request("CODE-REVIEW"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("名称");
        when(repository.existsByUserIdAndNameKey(1L, "review")).thenReturn(true);
        assertThatThrownBy(() -> service.install(1L, request("Review"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("名称");
        when(repository.countByUserId(1L)).thenReturn(100L);
        assertThatThrownBy(() -> service.install(1L, request("Another"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("100");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void invalidUploadFailsBeforeAnyPersistenceInteraction() {
        assertThatThrownBy(() -> service.install(1L, new SkillInstallRequest("Review", new SkillInstallRequest.Upload("run.sh", "exec"), null)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test void builtinRemainsResolvableAndCannotBeUninstalled() {
        assertThat(service.resolveEnabledSkill(1L, "code-review").prompt()).isEqualTo("Builtin prompt");
        assertThat(service.detail(1L, "code-review").managed()).isFalse();
        assertThatThrownBy(() -> service.uninstall(1L, "code-review")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("不能卸载");
        SysUserSettings userSettings = mock(SysUserSettings.class);
        when(settings.getOrCreate(1L)).thenReturn(userSettings);
        when(settings.parseDisabledSkills(userSettings)).thenReturn(List.of("code-review"));
        assertThatThrownBy(() -> service.resolveEnabledSkill(1L, "code-review")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("禁用");
    }

    private SkillInstallRequest request(String name) { return new SkillInstallRequest(name, new SkillInstallRequest.Upload("SKILL.md", "Read carefully"), null); }
    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }
}
