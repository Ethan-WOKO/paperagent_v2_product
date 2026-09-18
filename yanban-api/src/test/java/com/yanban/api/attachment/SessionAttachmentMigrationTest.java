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
    @Autowired com.yanban.api.settings.SharedModelCatalog catalog;
    @Autowired com.yanban.api.settings.UserSettingsService settingsService;
    @Autowired com.yanban.api.agent.AgentService agentService;
    @Autowired com.yanban.api.agent.AgentSessionService projectSessionService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.yanban.api.user.SysUserRepository users;
    @org.springframework.boot.test.mock.mockito.MockBean com.yanban.api.settings.ModelDiscoveryService discovery;

    private Long catalogUser(String name) {
        jdbc.update("INSERT INTO sys_users(username,password_hash) VALUES(?,'hash')",name);
        Long id=jdbc.queryForObject("SELECT id FROM sys_users WHERE username=?",Long.class,name);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.execute(status -> { em.persist(new com.yanban.api.settings.SysUserSettings(id,"deepseek",null,null,"deepseek-v4-flash","glm-5.2",null,"[]","[]",java.math.BigDecimal.ONE,8,false)); return null; });
        return id;
    }

    @Test @Transactional void automaticDiscoveryPreservesExclusionsAndBatchIsAtomic() {
        long id=catalog.saveProvider(null,new com.yanban.api.settings.SharedModelCatalog.ProviderInput("Auto","https://example.com/v1",null,"key",true));
        org.mockito.Mockito.when(discovery.discoverModels("https://example.com/v1/models","key")).thenReturn(java.util.List.of("one","two"));
        assertThat(catalog.sync(id)).allMatch(model -> model.approved());
        catalog.saveModels(id,new com.yanban.api.settings.SharedModelCatalog.ModelBatch(java.util.List.of(
                new com.yanban.api.settings.SharedModelCatalog.ModelInput("one",false,false),
                new com.yanban.api.settings.SharedModelCatalog.ModelInput("two",true,true))));
        org.mockito.Mockito.when(discovery.discoverModels("https://example.com/v1/models","key")).thenReturn(java.util.List.of("one","two","three"));
        var updated=catalog.sync(id);
        assertThat(updated).anySatisfy(model->{assertThat(model.modelName()).isEqualTo("one");assertThat(model.approved()).isFalse();});
        assertThat(updated).anySatisfy(model->{assertThat(model.modelName()).isEqualTo("two");assertThat(model.supportsVision()).isTrue();});
        assertThat(updated).anySatisfy(model->{assertThat(model.modelName()).isEqualTo("three");assertThat(model.approved()).isTrue();});
        assertThatThrownBy(()->catalog.saveModels(id,new com.yanban.api.settings.SharedModelCatalog.ModelBatch(java.util.List.of(
                new com.yanban.api.settings.SharedModelCatalog.ModelInput("two",false,false),
                new com.yanban.api.settings.SharedModelCatalog.ModelInput("unknown",true,false)))))
                .hasMessageContaining("不存在");
        assertThat(catalog.models(id)).anySatisfy(model->{assertThat(model.modelName()).isEqualTo("two");assertThat(model.approved()).isTrue();});
    }

    @Test @Transactional void sharedCatalogApprovalsSyncAndSecretBoundaries() throws Exception {
        Long user=catalogUser("catalog-user");
        long provider=catalog.saveProvider(null,new com.yanban.api.settings.SharedModelCatalog.ProviderInput("Qwen","https://example.com/v1","https://example.com/v1/models","private-shared-secret",true));
        org.mockito.Mockito.when(discovery.discoverModels("https://example.com/v1/models","private-shared-secret")).thenReturn(java.util.List.of("qwen-new"));
        var first=catalog.sync(provider).get(0);
        String key="shared-"+first.id();
        assertThat(first.approved()).isTrue();
        assertThat(catalog.availableModels()).hasSize(1);
        catalog.saveModel(provider,new com.yanban.api.settings.SharedModelCatalog.ModelInput("qwen-new",false,false));
        assertThat(catalog.availableModels()).isEmpty();
        assertThatThrownBy(()->settingsService.resolveModelEndpoint(user,key,"qwen-new")).hasMessageContaining("未获批准");
        catalog.saveModel(provider,new com.yanban.api.settings.SharedModelCatalog.ModelInput("qwen-new",true,true));
        var endpoint=settingsService.resolveModelEndpoint(user,key,"qwen-new");
        assertThat(endpoint.apiKey()).isEqualTo("private-shared-secret");
        assertThat(endpoint.apiUrl()).isEqualTo("https://example.com/v1/chat/completions");
        assertThatCode(()->visionPolicy.require(user,key,"qwen-new")).doesNotThrowAnyException();
        String token="Bearer "+jwt.createAccessToken(user,"catalog-user");
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/settings").header("Authorization",token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains(key).doesNotContain("private-shared-secret","https://example.com");
        // Shared credentials are available only to normal metered chat, not personal model test CRUD.
        assertThat(settingsService.listCustomModels(user)).noneMatch(item -> item.providerKey().startsWith("shared-"));
        org.mockito.Mockito.when(discovery.discoverModels("https://example.com/v1/models","private-shared-secret")).thenReturn(java.util.List.of("qwen-new","qwen-newer"));
        var second=catalog.sync(provider);
        assertThat(second).anySatisfy(model->{assertThat(model.modelName()).isEqualTo("qwen-new");assertThat(model.approved()).isTrue();assertThat(model.supportsVision()).isTrue();});
        assertThat(second).anySatisfy(model->{assertThat(model.modelName()).isEqualTo("qwen-newer");assertThat(model.approved()).isTrue();});
        catalog.saveProvider(provider,new com.yanban.api.settings.SharedModelCatalog.ProviderInput("Qwen","https://example.com/v1","https://example.com/v1/models",null,false));
        assertThat(catalog.availableModels()).isEmpty();
        assertThatThrownBy(()->settingsService.resolveModelEndpoint(user,key,"qwen-new")).hasMessageContaining("已停用");
    }

    @Test @Transactional void synchronizationFailureRetainsCatalogAndMissingModelsAreUnavailable() {
        long provider=catalog.saveProvider(null,new com.yanban.api.settings.SharedModelCatalog.ProviderInput("Sync","https://example.com/v1","https://example.com/models","key",true));
        org.mockito.Mockito.when(discovery.discoverModels("https://example.com/models","key")).thenReturn(java.util.List.of("old-model"));
        var model=catalog.sync(provider).get(0);
        catalog.saveModel(provider,new com.yanban.api.settings.SharedModelCatalog.ModelInput("old-model",true,false));
        org.mockito.Mockito.when(discovery.discoverModels("https://example.com/models","key")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"offline"));
        assertThatThrownBy(()->catalog.sync(provider)).hasMessageContaining("offline");
        assertThat(catalog.models(provider).get(0).approved()).isTrue();
        org.mockito.Mockito.doReturn(java.util.List.of("new-model")).when(discovery).discoverModels("https://example.com/models","key");
        catalog.sync(provider);
        assertThatThrownBy(()->catalog.resolve("shared-"+model.id(),"old-model")).hasMessageContaining("不可用");
    }

    @Test @Transactional void onlyAdministratorsCanConfigureSharedCredentialsAndApprovals() throws Exception {
        Long user=catalogUser("catalog-admin-test");
        String ordinary="Bearer "+jwt.createAccessToken(user,"catalog-admin-test");
        String path="/api/v1/admin/model-providers";
        String body="{\"name\":\"Shared\",\"chatUrl\":\"https://example.com/v1\",\"apiKey\":\"secret-not-returned\",\"enabled\":true}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",ordinary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",ordinary).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        jdbc.update("UPDATE sys_users SET role='ADMIN' WHERE id=?",user);em.clear();
        String admin="Bearer "+jwt.createAccessToken(users.findById(user).orElseThrow());
        var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",admin).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        long id=Long.parseLong(created.getResponse().getContentAsString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path+"/"+id+"/models").header("Authorization",admin).contentType("application/json").content("{\"modelName\":\"manual-model\",\"approved\":true,\"supportsVision\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var listed=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listed).contains("apiKeyConfigured").doesNotContain("secret-not-returned","apiKeyEncrypted");
        assertThat(catalog.models(id).get(0).approved()).isTrue();
        Long batchUser=catalogUser("batch-ordinary-user");
        String batchOrdinary="Bearer "+jwt.createAccessToken(batchUser,"batch-ordinary-user");
        String batch="{\"models\":[{\"modelName\":\"manual-model\",\"approved\":false,\"supportsVision\":true}]}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path+"/"+id+"/models/batch").header("Authorization",batchOrdinary).contentType("application/json").content(batch))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path+"/"+id+"/models/batch").header("Authorization",admin).contentType("application/json").content(batch))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].approved").value(false));
        assertThat(catalog.models(id).get(0).approved()).isFalse();

    }

    @Test void concurrentNewWorkspaceSessionsReuseOneEmptySession() throws Exception {
        Long user=catalogUser("concurrent-empty-session");
        var request=new com.yanban.api.agent.CreateSessionRequest("New",null,null,null,true);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(()->{gate.await(); return agentService.createSession(user,request).id();}));
            gate.countDown();
            var ids=new java.util.HashSet<Long>();
            for(var future:futures) ids.add(future.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(sessions.countByUserId(user)).isEqualTo(1);
            Long session=ids.iterator().next();
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{em.persist(new AgentMessage(session,user,"user","hello",null,null));return null;});
            assertThat(agentService.createSession(user,request).id()).isNotEqualTo(session);
        } finally { pool.shutdownNow(); }
    }

    @Test @Transactional void emptySessionsNeverCrossUserOrProjectBoundaries() {
        Long user=catalogUser("scoped-empty-user");
        Long other=catalogUser("scoped-empty-other");
        for(String name:java.util.List.of("project-a","project-b")) jdbc.update("INSERT INTO projects(user_id,name,root_type,root_path,canonical_root_path,include_rules,ignore_rules) VALUES(?,?,'LOCAL','/tmp','/tmp','[]','[]')",user,name);
        Long a=jdbc.queryForObject("SELECT id FROM projects WHERE user_id=? AND name='project-a'",Long.class,user);
        Long b=jdbc.queryForObject("SELECT id FROM projects WHERE user_id=? AND name='project-b'",Long.class,user);
        var request=new com.yanban.api.agent.CreateSessionRequest("New",null,null,null,true);
        long workspace=agentService.createSession(user,request).id();
        long first=projectSessionService.createProjectSession(user,a,request,"New").id();
        assertThat(projectSessionService.createProjectSession(user,a,request,"New").id()).isEqualTo(first);
        assertThat(projectSessionService.createProjectSession(user,b,request,"New").id()).isNotIn(first,workspace);
        assertThat(agentService.createSession(other,request).id()).isNotIn(first,workspace);
        assertThat(agentService.createSession(user,request).id()).isEqualTo(workspace);
    }

    @Autowired AttachmentVisionPolicy visionPolicy;

    @Test @Transactional void modelApiCreatesAndUpdatesVisionWithOwnerIsolation() throws Exception {
        jdbc.update("INSERT INTO sys_users(username,password_hash) VALUES('vision-api-owner','hash'),('vision-api-other','hash')");
        Long owner = jdbc.queryForObject("SELECT id FROM sys_users WHERE username='vision-api-owner'", Long.class);
        Long other = jdbc.queryForObject("SELECT id FROM sys_users WHERE username='vision-api-other'", Long.class);
        // Settings initialization uses REQUIRES_NEW; seed settings in this test transaction.
        em.persist(new com.yanban.api.settings.SysUserSettings(owner, "deepseek", null, null,
                "deepseek-v4-flash", "glm-5.2", null, "[]", "[]", java.math.BigDecimal.ONE, 8, false));
        em.flush();
        String token = "Bearer " + jwt.createAccessToken(owner, "vision-api-owner");
        String body = "{\"label\":\"Qwen\",\"apiUrl\":\"https://example.com/chat/completions\",\"modelName\":\"qwen3.8-max\",\"supportsVision\":true}";
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/models")
                .header("Authorization", token).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.supportsVision").value(true))
                .andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        Long id = json.path("id").asLong();
        String provider = json.path("providerKey").asText();
        em.clear();
        assertThatCode(() -> visionPolicy.require(owner, provider, "qwen3.8-max")).doesNotThrowAnyException();
        assertThatThrownBy(() -> visionPolicy.require(other, provider, "qwen3.8-max")).hasMessageContaining("尚未配置");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/models/" + id)
                .header("Authorization", "Bearer " + jwt.createAccessToken(other, "vision-api-other"))
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/models/" + id)
                .header("Authorization", token).contentType("application/json").content(body.replace("true", "false")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.supportsVision").value(false));
        em.clear();
        assertThatThrownBy(() -> visionPolicy.require(owner, provider, "qwen3.8-max")).hasMessageContaining("未启用");
    }

    @Test @Transactional void modelVisionCapabilitySurvivesReload() {
        jdbc.update("INSERT INTO sys_users(username,password_hash) VALUES('vision-owner','hash')");
        Long user = jdbc.queryForObject("SELECT id FROM sys_users WHERE username='vision-owner'", Long.class);
        var model = new com.yanban.api.settings.UserModel(user, "custom-vision", "Qwen", "qwen3.8-max", null, null, false, 1);
        em.persist(model); em.flush(); em.clear();
        assertThat(em.find(com.yanban.api.settings.UserModel.class, model.getId()).getSupportsVision()).isNull();
        var loaded = em.find(com.yanban.api.settings.UserModel.class, model.getId());
        loaded.setSupportsVision(true); em.flush(); em.clear();
        assertThat(em.find(com.yanban.api.settings.UserModel.class, model.getId()).getSupportsVision()).isTrue();
    }

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
