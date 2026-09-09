package com.yanban.api.skills;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.skills.SkillRegistry;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:user_skills_persistence_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-h2",
        "spring.jpa.hibernate.ddl-auto=none"
})
// The H2 migrations retain MySQL syntax; the default slice replacement loses MODE=MySQL.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SkillsService.class, SkillUploadParser.class, UserSkillsPersistenceTest.JsonConfig.class})
class UserSkillsPersistenceTest {
    @TestConfiguration static class JsonConfig { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }
    @MockBean SkillRegistry registry;
    @MockBean UserSettingsService settings;
    @Autowired SkillsService skills;
    @Autowired SysUserRepository users;
    @Autowired UserSkillRepository installed;
    @Autowired EntityManager entities;

    @BeforeEach void setup() {
        when(registry.list()).thenReturn(List.of());
        when(settings.parseDisabledSkills(any())).thenReturn(List.of());
    }

    @Test void survivesReloadAndPersistsEnablementAndDeletion() {
        long user = users.saveAndFlush(new SysUser("skill_persist_owner", "hash")).getId();
        var created = skills.install(user, request());
        reload();
        assertThat(skills.listSkills(user)).extracting(SkillListItemResponse::id).containsExactly(created.id());
        assertThat(skills.resolveEnabledSkill(user, created.id()).prompt()).contains("persisted instructions");
        skills.setEnabled(user, created.id(), false);
        reload();
        assertThat(skills.detail(user, created.id()).enabled()).isFalse();
        skills.setEnabled(user, created.id(), true);
        reload();
        assertThat(skills.resolveEnabledSkill(user, created.id()).allowedTools()).containsExactlyInAnyOrderElementsOf(SkillUploadParser.DEFAULT_TOOLS);
        skills.uninstall(user, created.id());
        reload();
        assertThat(installed.findByIdAndUserId(created.id(), user)).isEmpty();
        assertThatThrownBy(() -> skills.resolveEnabledSkill(user, created.id())).isInstanceOf(ResponseStatusException.class);
    }

    @Test void sameNameAcrossOwnersRemainsIsolatedAfterReload() {
        long a = users.saveAndFlush(new SysUser("skill_owner_a", "hash")).getId();
        long b = users.saveAndFlush(new SysUser("skill_owner_b", "hash")).getId();
        var first = skills.install(a, request());
        var second = skills.install(b, request());
        reload();
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(skills.listSkills(a)).extracting(SkillListItemResponse::id).containsExactly(first.id());
        assertThat(skills.listSkills(b)).extracting(SkillListItemResponse::id).containsExactly(second.id());
        assertThat(installed.findByIdAndUserId(first.id(), b)).isEmpty();
        assertThatThrownBy(() -> skills.detail(b, first.id())).isInstanceOf(ResponseStatusException.class);
    }

    private void reload() { entities.flush(); entities.clear(); }
    private SkillInstallRequest request() { return new SkillInstallRequest("Review", new SkillInstallRequest.Upload("SKILL.md", "persisted instructions"), null); }
}
