package com.yanban.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.security.JwtService;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_admin_governance_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-h2",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890",
        "yanban.invite.enabled=true",
        "yanban.invite.codes="
})
class AdminGovernanceIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired SysUserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    @Test
    void onlyAdministratorCanDeleteUserAndDeletionInvalidatesLoginImmediately() throws Exception {
        SysUser administrator = createUser("governance_admin_delete", true);
        SysUser target = createUser("governance_target_delete", false);
        String targetToken = token(target);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", target.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.fieldErrors").isMap());

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", target.getId())
                        .header("Authorization", "Bearer " + token(administrator)))
                .andExpect(status().isNoContent());

        assertThat(users.findById(target.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(users.findByIdAndDeletedAtIsNull(target.getId())).isEmpty();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"governance_target_delete\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("账号不存在"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'governance_target_delete')]").isEmpty());
    }

    @Test
    void administratorCannotDeleteOwnOrAnotherAdministratorAccount() throws Exception {
        SysUser first = createUser("governance_admin_first", true);
        SysUser second = createUser("governance_admin_second", true);
        String token = token(first);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", first.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SELF_DELETE_FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", second.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_DELETE_FORBIDDEN"));
    }

    @Test
    void generatedInviteCanBeSavedTrackedAndDeletedWithoutLosingUsageFacts() throws Exception {
        SysUser administrator = createUser("governance_admin_invite", true);
        String adminToken = token(administrator);

        MvcResult generated = mockMvc.perform(post("/api/v1/admin/invite-codes/generate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not(blankOrNullString())))
                .andReturn();
        String code = json.readTree(generated.getResponse().getContentAsString()).get("code").asText();
        assertThat(code).matches("^YB-[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/invite-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"maxUses\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.remainingUses").value(2))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn();
        JsonNode invite = json.readTree(created.getResponse().getContentAsString());
        long inviteId = invite.get("id").asLong();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"governance_invited_user\",\"password\":\"password123\","
                                + "\"inviteCode\":\"" + code + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/invite-codes")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + inviteId + ")].usedCount").value(1))
                .andExpect(jsonPath("$[?(@.id == " + inviteId + ")].remainingUses").value(1));

        mockMvc.perform(delete("/api/v1/admin/invite-codes/{inviteCodeId}", inviteId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"governance_deleted_invite_user\",\"password\":\"password123\","
                                + "\"inviteCode\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_DELETED"))
                .andExpect(jsonPath("$.fieldErrors.inviteCode").value("邀请码已删除"));
    }

    private SysUser createUser(String username, boolean administrator) {
        SysUser user = new SysUser(username, passwordEncoder.encode("password123"));
        if (administrator) user.setRole("ADMIN");
        return users.saveAndFlush(user);
    }

    private String token(SysUser user) {
        user.beginNewLogin();
        users.saveAndFlush(user);
        return jwtService.createAccessToken(user);
    }
}
