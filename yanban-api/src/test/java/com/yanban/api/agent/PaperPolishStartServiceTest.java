package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.paper.domain.*;
import com.yanban.paper.service.*;
import com.yanban.paper.web.PaperProcessRequest;
import com.yanban.paper.web.PaperTaskResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

class PaperPolishStartServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private PaperPolishInputResolver inputs;
    private PaperTaskService pipeline;
    private PaperTaskRepository tasks;
    private PaperOrchestrator orchestrator;
    private PaperPolishStartService service;
    private final List<BooleanSupplier> claims = new CopyOnWriteArrayList<>();
    private final AtomicInteger taskIds = new AtomicInteger(100);
    static final String TEX = "\\documentclass{article}\n\\begin{document}\nAn original paper.\n\\end{document}";

    @BeforeEach void setup() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:paper_start_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE sys_users(id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO sys_users VALUES (7), (8)");
        jdbc.execute("CREATE TABLE paper_tasks(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, status VARCHAR(32))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V103__create_paper_tool_starts.sql")).execute(data);
        tasks = mock(PaperTaskRepository.class);
        inputs = spy(new PaperPolishInputResolver(tasks, mock(PaperTaskArtifactRepository.class),
                mock(PaperStorageService.class), mock(KbDocumentRepository.class), mock(ObjectProvider.class),
                new KnowledgeStorageProperties(), mock(ProjectService.class)));
        pipeline = mock(PaperTaskService.class);
        orchestrator = mock(PaperOrchestrator.class);
        when(pipeline.createTaskForTool(anyLong(), any(), anyString())).thenAnswer(call -> {
            long user = call.getArgument(0);
            PaperProcessRequest request = call.getArgument(1);
            assertThat(new String(request.mainTex().getBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(TEX);
            long id = taskIds.incrementAndGet();
            jdbc.update("INSERT INTO paper_tasks VALUES (?, ?, 'PENDING')", id, user);
            return PaperTaskResponse.from(task(id, user, "PENDING"), 20, false);
        });
        when(tasks.findByIdAndUserId(anyLong(), anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0), user = call.getArgument(1);
            return jdbc.query("SELECT status FROM paper_tasks WHERE id = ? AND user_id = ?",
                    (rs, row) -> task(id, user, rs.getString(1)), id, user).stream().findFirst();
        });
        doAnswer(call -> { claims.add(call.getArgument(1)); return null; }).when(orchestrator).startTaskIfClaimed(anyLong(), any());
        service = new PaperPolishStartService(jdbc, new DataSourceTransactionManager(data), inputs, pipeline, tasks, orchestrator);
        ToolExecutionContext.setInvocationScope("session.1.turn.2");
    }

    @AfterEach void cleanup() { ToolExecutionContext.clear(); }

    @Test void commitsOneRealPipelineTaskAndReplaysBeforeReadingSourceAgain() {
        var first = service.start(7L, null, args());
        var second = service.start(7L, null, args());
        assertThat(first.replayed()).isFalse();
        assertThat(first.terminal()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.sourceSha256()).isEqualTo(PaperPolishStartService.digest(TEX));
        verify(inputs, times(1)).resolve(eq(7L), isNull(), any());
        verify(pipeline, times(1)).createTaskForTool(eq(7L), any(), anyString());
        assertThat(claims.get(0).getAsBoolean()).isTrue();
        assertThat(claims.get(1).getAsBoolean()).isFalse();
    }

    @Test void changedInputAtSameScopeConflictsBeforeAnySecondResolution() {
        service.start(7L, null, args());
        assertThatThrownBy(() -> service.start(7L, null, args().put("latexText", TEX + "\nchanged")))
                .hasMessageContaining("409");
        verify(inputs, times(1)).resolve(anyLong(), isNull(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_tasks", Integer.class)).isEqualTo(1);
    }

    @Test void recreatedAdapterAndNewChatTurnReuseTheDurableHttpRequestBinding() {
        ToolExecutionContext.setInvocationScope(AgentService.paperInvocationScope(1L, 10L, "same-http-request"));
        var first = service.start(7L, null, args());
        var restarted = new PaperPolishStartService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()),
                inputs, pipeline, tasks, orchestrator);
        ToolExecutionContext.setInvocationScope(AgentService.paperInvocationScope(1L, 999L, "same-http-request"));
        assertThat(restarted.start(7L, null, args()).taskId()).isEqualTo(first.taskId());
        assertThatThrownBy(() -> restarted.start(7L, null, args().put("targetLanguage", "zh"))).hasMessageContaining("409");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_tasks", Integer.class)).isEqualTo(1);
        verify(pipeline, times(1)).createTaskForTool(eq(7L), any(), anyString());
    }

    @Test void concurrentStartsConvergeOnOneTaskAndOnlyOneWorkerClaim() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<PaperPolishStartService.StartResult> run = () -> {
                ToolExecutionContext.setInvocationScope("parallel-turn");
                try { start.await(); return service.start(7L, null, args()); }
                finally { ToolExecutionContext.clear(); }
            };
            var a = pool.submit(run); var b = pool.submit(run); start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).taskId()).isEqualTo(b.get(10, TimeUnit.SECONDS).taskId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_tool_starts", Integer.class)).isEqualTo(1);
            var c = pool.submit(() -> claims.get(0).getAsBoolean());
            var d = pool.submit(() -> claims.get(1).getAsBoolean());
            assertThat(List.of(c.get(10, TimeUnit.SECONDS), d.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
    }

    @Test void afterCreateBeforeDispatchFailureCanReplayTheCommittedTask() {
        doThrow(new RejectedExecutionException("queue unavailable")).doAnswer(call -> {
            claims.add(call.getArgument(1)); return null;
        }).when(orchestrator).startTaskIfClaimed(anyLong(), any());
        assertThatThrownBy(() -> service.start(7L, null, args())).isInstanceOf(RejectedExecutionException.class);
        assertThat(jdbc.queryForObject("SELECT dispatch_state FROM paper_tool_starts", String.class)).isEqualTo("READY");
        var replay = service.start(7L, null, args());
        assertThat(replay.replayed()).isTrue();
        assertThat(claims.get(0).getAsBoolean()).isTrue();
        verify(pipeline, times(1)).createTaskForTool(anyLong(), any(), anyString());
    }

    @Test void queuedButNotYetClaimedTaskCanBeRecoveredAndCancelledTaskDoesNotRun() {
        var first = service.start(7L, null, args());
        service.start(7L, null, args());
        jdbc.update("UPDATE paper_tasks SET status = 'CANCELLED' WHERE id = ?", first.taskId());
        assertThat(claims.get(1).getAsBoolean()).isFalse();
        assertThat(service.start(7L, null, args()).status()).isEqualTo("CANCELLED");
    }

    @Test void failuresRollBackBothBindingAndTask() {
        doAnswer(call -> {
            jdbc.update("INSERT INTO paper_tasks VALUES (999,7,'PENDING')");
            throw new IllegalStateException("storage failed");
        }).when(pipeline).createTaskForTool(anyLong(), any(), anyString());
        assertThatThrownBy(() -> service.start(7L, null, args())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_tasks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paper_tool_starts", Integer.class)).isZero();
        verifyNoInteractions(orchestrator);
    }

    @Test void sameScopeIsOwnerIsolatedAndNewScopeCreatesNewTask() {
        var a = service.start(7L, null, args());
        var b = service.start(8L, null, args());
        ToolExecutionContext.setInvocationScope("different-turn");
        var c = service.start(7L, null, args());
        assertThat(Set.of(a.taskId(), b.taskId(), c.taskId())).hasSize(3);
    }

    @Test void noServerScopeCannotCreateTask() {
        ToolExecutionContext.clear();
        assertThatThrownBy(() -> service.start(7L, null, args())).hasMessageContaining("服务器");
        verifyNoInteractions(pipeline, orchestrator);
    }

    @Test void changedParameterConflictsAndForeignTaskCannotStart() {
        service.start(7L, null, args());
        assertThatThrownBy(() -> service.start(7L, null, args().put("targetLanguage", "zh"))).hasMessageContaining("409");
        ToolExecutionContext.setInvocationScope("foreign-source");
        assertThatThrownBy(() -> service.start(8L, null, json.createObjectNode().put("sourceTaskId", 101).put("targetLanguage", "en")))
                .hasMessageContaining("不可访问");
    }

    private ObjectNode args() { return json.createObjectNode().put("latexText", TEX).put("targetLanguage", "en"); }
    private static PaperTask task(long id, long user, String status) {
        PaperTask task = new PaperTask(user, "paper", "paper.tex", "owned/source.tex", status, "en", "UPLOAD_RECEIVED", null);
        ReflectionTestUtils.setField(task, "id", id); return task;
    }
}
