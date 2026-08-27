package com.yanban.api.demo;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.project.Project;
import com.yanban.api.project.ProjectRepository;
import com.yanban.api.project.ProjectService;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbChunkUploadRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.KnowledgeIndexService;
import com.yanban.knowledge.service.KnowledgeIngestionService;
import com.yanban.paper.domain.PaperTaskRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoAccountService {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountService.class);
    private static final List<SeedDocument> SEED_DOCUMENTS = List.of(
            new SeedDocument("yanban-demo-project.md", "demo/kb/yanban-demo-project.md"),
            new SeedDocument("yanban-demo-rag-notes.md", "demo/kb/yanban-demo-rag-notes.md"),
            new SeedDocument("yanban-demo-lab-schedule.md", "demo/kb/yanban-demo-lab-schedule.md")
    );

    private final DemoProperties properties;
    private final SysUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoUserInitializer demoUserInitializer;
    private final UserSettingsService userSettingsService;
    private final KnowledgeIngestionService ingestionService;
    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbChunkUploadRepository chunkUploads;
    private final KnowledgeIndexService indexService;
    private final AgentSessionRepository sessions;
    private final DemoChatArchiveService chatArchives;
    private final PaperTaskRepository paperTasks;
    private final ProjectRepository projects;
    private final ProjectService projectService;

    public DemoAccountService(DemoProperties properties,
                              SysUserRepository users,
                              PasswordEncoder passwordEncoder,
                              DemoUserInitializer demoUserInitializer,
                              UserSettingsService userSettingsService,
                              KnowledgeIngestionService ingestionService,
                              KbDocumentRepository documents,
                              KbChunkRepository chunks,
                              KbChunkUploadRepository chunkUploads,
                              KnowledgeIndexService indexService,
                              AgentSessionRepository sessions,
                              DemoChatArchiveService chatArchives,
                              PaperTaskRepository paperTasks,
                              ProjectRepository projects,
                              ProjectService projectService) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoUserInitializer = demoUserInitializer;
        this.userSettingsService = userSettingsService;
        this.ingestionService = ingestionService;
        this.documents = documents;
        this.chunks = chunks;
        this.chunkUploads = chunkUploads;
        this.indexService = indexService;
        this.sessions = sessions;
        this.chatArchives = chatArchives;
        this.paperTasks = paperTasks;
        this.projects = projects;
        this.projectService = projectService;
    }

    @Transactional
    public SysUser ensureDemoUserReady() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo 入口未开启。");
        }
        SysUser user = ensureDemoUser();
        userSettingsService.getOrCreate(user.getId());
        ensureSeedDocuments(user.getId());
        return user;
    }

    /**
     * A shared demo starts each visitor with an empty conversation and empty projects.
     * Seed knowledge and paper examples are intentionally left untouched.
     */
    @Transactional
    public SysUser prepareForLogin() {
        SysUser user = ensureDemoUserReady();
        clearTransientDemoData(user.getId());
        return user;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initializeOnStartup() {
        if (!properties.isEnabled() || !properties.isSeedOnStartup()) {
            return;
        }
        try {
            ensureDemoUserReady();
            log.info("Demo account initialized username={}", properties.getUsername());
        } catch (Exception ex) {
            log.error("Failed to initialize demo account. Demo login will retry on demand.", ex);
        }
    }

    @Scheduled(cron = "${yanban.demo.reset-cron:0 30 3 * * *}")
    @Transactional
    public void scheduledReset() {
        if (!properties.isEnabled()) {
            return;
        }
        SysUser user = ensureDemoUser();
        resetDemoData(user.getId());
        log.info("Demo account reset userId={}", user.getId());
    }

    @Transactional
    public void resetDemoData(Long userId) {
        clearTransientDemoData(userId);
    }

    private void clearTransientDemoData(Long userId) {
        chatArchives.archiveCurrentSessions(userId);
        sessions.deleteAll(sessions.findByUserIdOrderByUpdatedAtDesc(userId));
        for (Project project : projects.findByUserIdOrderByUpdatedAtDesc(userId)) {
            projectService.delete(userId, project.getId());
        }
    }

    private SysUser ensureDemoUser() {
        String username = normalizeUsername(properties.getUsername());
        SysUser existing = users.findByUsername(username).orElse(null);
        if (existing != null) {
            return requireDemoAccount(existing, username);
        }
        try {
            return demoUserInitializer.create(
                    username, passwordEncoder.encode(UUID.randomUUID().toString()));
        } catch (DataIntegrityViolationException concurrentCreation) {
            return users.findByUsername(username)
                    .map(user -> requireDemoAccount(user, username))
                    .orElseThrow(() -> concurrentCreation);
        }
    }

    private SysUser requireDemoAccount(SysUser user, String username) {
        if (!DemoAccessService.ACCOUNT_TYPE_DEMO.equalsIgnoreCase(user.getAccountType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Demo 用户名已被普通账号占用：" + username);
        }
        return user;
    }

    private void ensureSeedDocuments(Long userId) {
        if (documents.countByUserIdAndSourceType(userId, DemoAccessService.SOURCE_TYPE_DEMO_SEED) == SEED_DOCUMENTS.size()) {
            return;
        }
        deleteDocuments(documents.findByUserIdAndSourceType(userId, DemoAccessService.SOURCE_TYPE_DEMO_SEED));
        seedDocuments(userId);
    }

    private void seedDocuments(Long userId) {
        for (SeedDocument seed : SEED_DOCUMENTS) {
            ingestionService.ingestText(
                    userId,
                    seed.filename(),
                    readResource(seed.resourcePath()),
                    false,
                    DemoAccessService.SOURCE_TYPE_DEMO_SEED,
                    "text/markdown"
            );
        }
    }

    private void deleteDocuments(List<KbDocument> userDocuments) {
        for (KbDocument document : userDocuments) {
            chunks.deleteByDocumentId(document.getId());
            try {
                indexService.deleteByDocumentId(document.getId());
            } catch (Exception ex) {
                log.warn("Failed to delete index entries for demo document id={}", document.getId(), ex);
            }
            documents.delete(document);
        }
        documents.flush();
    }

    private String readResource(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Demo 文档失败：" + resourcePath, ex);
        }
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return "demo";
        }
        return username.trim();
    }

    private record SeedDocument(String filename, String resourcePath) {
    }
}
