package com.yanban.api.invite;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_invite_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-h2",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890",
        "yanban.invite.enabled=true",
        "yanban.invite.codes=TEST-INVITE-001,TEST-INVITE-002",
        "yanban.invite.max-uses=2"
})
class InviteCodeIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    InviteCodeRepository inviteCodeRepository;

    @Test
    void registerRequiresInviteCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"nocode_user\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_REQUIRED"))
                .andExpect(jsonPath("$.message").value("请填写邀请码"))
                .andExpect(jsonPath("$.fieldErrors.inviteCode").value("请填写邀请码"));
    }

    @Test
    void registerWithValidInviteCodeSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"code_user1\",\"password\":\"password123\",\"inviteCode\":\"TEST-INVITE-001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())));

        InviteCode code = inviteCodeRepository.findByCode("TEST-INVITE-001").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(code.getUsedCount()).isEqualTo(1);
    }

    @Test
    void registerWithInvalidInviteCodeFails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"badcode_user\",\"password\":\"password123\",\"inviteCode\":\"DOES-NOT-EXIST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_INVALID"))
                .andExpect(jsonPath("$.message").value("邀请码无效"));
    }

    @Test
    void inviteCodeExhaustedAfterMaxUses() throws Exception {
        // TEST-INVITE-002 has max-uses=2
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"code_user2\",\"password\":\"password123\",\"inviteCode\":\"TEST-INVITE-002\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"code_user3\",\"password\":\"password123\",\"inviteCode\":\"TEST-INVITE-002\"}"))
                .andExpect(status().isCreated());

        // Third use should be rejected
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"code_user4\",\"password\":\"password123\",\"inviteCode\":\"TEST-INVITE-002\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_EXHAUSTED"))
                .andExpect(jsonPath("$.message").value("邀请码使用次数已达上限"));
    }
}
