package com.yanban.api.attachment;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yanban.core.agent.*;
import jakarta.persistence.EntityManager;
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties={
 "spring.datasource.url=jdbc:h2:mem:attachment_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=none","spring.flyway.enabled=true","spring.kafka.listener.auto-startup=false",
 "yanban.jwt.secret=test_secret_123456789012345678901234567890","yanban.memory.distillation.enabled=false"
})
class SessionAttachmentMigrationTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.yanban.api.security.JwtService jwt;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.boot.test.mock.mockito.MockBean AttachmentStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentSessionRepository sessions;
    @Autowired SessionAttachmentRepository attachments;
    @Autowired EntityManager em;
    @Test @Transactional void persistsAndReloadsAttachmentWithoutKnowledgeRows() {
        jdbc.update("INSERT INTO sys_users(username,password_hash) VALUES('attachment-owner','hash')");
        Long user=jdbc.queryForObject("SELECT id FROM sys_users WHERE username='attachment-owner'",Long.class);
        var session=sessions.saveAndFlush(new AgentSession(user,"attachment session","custom","test",4,false));
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents",Long.class);
        var a=new SessionAttachment(user,session.getId(),"notes.txt","text/plain","private-key",5);
        a.status="READY";a.extractedText="hello";attachments.saveAndFlush(a);em.clear();
        var loaded=attachments.findByUserIdAndSessionIdAndActiveTrueOrderByIdAsc(user,session.getId());
        assertThat(loaded).hasSize(1);assertThat(loaded.get(0).extractedText).isEqualTo("hello");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents",Long.class)).isEqualTo(before);
        jdbc.update("DELETE FROM agent_sessions WHERE id=?",session.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_session_attachments WHERE session_id=?",Long.class,session.getId())).isZero();
    }
    @Test @Transactional void authenticatedUploadAndReloadNeverCreateKnowledgeAndOtherUsersCannotRead() throws Exception {
        jdbc.update("INSERT INTO sys_users(username,password_hash) VALUES('attachment-http-owner','hash'),('attachment-http-other','hash')");
        Long user=jdbc.queryForObject("SELECT id FROM sys_users WHERE username='attachment-http-owner'",Long.class);
        Long other=jdbc.queryForObject("SELECT id FROM sys_users WHERE username='attachment-http-other'",Long.class);
        var session=sessions.saveAndFlush(new AgentSession(user,"http attachment","custom","test",4,false));
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents",Long.class);
        String path="/api/v1/agent/sessions/"+session.getId()+"/attachments";
        String token="Bearer "+jwt.createAccessToken(user,"attachment-http-owner");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path)
                .file(new org.springframework.mock.web.MockMultipartFile("file","notes.txt","text/plain","private content".getBytes()))
                .header("Authorization",token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.objectKey").doesNotExist())
                .andReturn();
        long id=mapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(id));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization","Bearer "+jwt.createAccessToken(other,"attachment-http-other")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path+"/"+id).header("Authorization",token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertThat(attachments.findById(id)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents",Long.class)).isEqualTo(before);
    }
    @Test void productionMigrationIsAdditiveAndCompatible() {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:attachment_production;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var sql=new JdbcTemplate(source);sql.execute("CREATE TABLE agent_sessions(id BIGINT PRIMARY KEY)");sql.update("INSERT INTO agent_sessions VALUES(1)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V105__workspace_session_attachments.sql")).execute(source);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM agent_sessions",Long.class)).isEqualTo(1);
    }
}
