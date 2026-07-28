package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentTaskEventRecorder;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class LiteratureSearchTaskServiceTest {

    private static final Long USER_ID = 11L;
    private static final Long TASK_ID = 101L;

    private LiteratureSearchTaskRepository tasks;
    private LiteratureSearchTaskService service;

    @BeforeEach
    void setUp() {
        tasks = mock(LiteratureSearchTaskRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LiteratureSearchTaskPublisher> provider = mock(ObjectProvider.class);
        service = new LiteratureSearchTaskService(tasks, provider, eventProvider(null));
    }

    @Test
    void createTaskNormalizesInputAndSavesPendingTask() {
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> {
            LiteratureSearchTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", TASK_ID);
            return task;
        });

        LiteratureSearchTaskService.TaskStartResult result = service.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("  hybrid   RAG  ", 99, 2021, null, "req-1", 7L)
        );

        assertThat(result.idempotent()).isFalse();
        assertThat(result.task().getId()).isEqualTo(TASK_ID);
        assertThat(result.task().getQuery()).isEqualTo("hybrid RAG");
        assertThat(result.task().getTopK()).isEqualTo(20);
        assertThat(result.task().getIncludeBibtex()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_PENDING);
        assertThat(result.task().getCurrentStage()).isEqualTo("QUEUED");
        assertThat(result.task().getProjectId()).isEqualTo(7L);
    }

    @Test
    void createTaskPublishesNewTaskMessage() {
        LiteratureSearchTaskPublisher publisher = mock(LiteratureSearchTaskPublisher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LiteratureSearchTaskPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        service = new LiteratureSearchTaskService(tasks, provider, eventProvider(null));
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> {
            LiteratureSearchTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", TASK_ID);
            return task;
        });

        LiteratureSearchTaskService.TaskStartResult result = service.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-1", null)
        );

        assertThat(result.idempotent()).isFalse();
        verify(publisher).publishTaskCreated(result.task());
    }

    @Test
    void createTaskRecordsCreatedEvent() {
        AgentTaskEventRecorder eventRecorder = mock(AgentTaskEventRecorder.class);
        service = new LiteratureSearchTaskService(tasks, publisherProvider(null), eventProvider(eventRecorder));
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> {
            LiteratureSearchTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", TASK_ID);
            return task;
        });

        service.createTask(USER_ID, new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-1", null));

        verify(eventRecorder).recordSafely(argThat(event ->
                event.taskType().equals(AgentTaskEventRecorder.TASK_TYPE_LITERATURE_SEARCH)
                        && event.taskId().equals(TASK_ID)
                        && event.userId().equals(USER_ID)
                        && event.eventType().equals("TASK_CREATED")
                        && event.stage().equals("QUEUED")
                        && event.status().equals(LiteratureSearchTaskService.STATUS_PENDING)
        ));
    }

    @Test
    void duplicateClientRequestReturnsExistingTask() {
        LiteratureSearchTask existing = task(LiteratureSearchTaskService.STATUS_PENDING);
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.of(existing));

        LiteratureSearchTaskService.TaskStartResult result = service.createTask(
                USER_ID,
                new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-1", null)
        );

        assertThat(result.idempotent()).isTrue();
        assertThat(result.task()).isSameAs(existing);
        verify(tasks, never()).save(any());
    }

    @Test
    void duplicateClientRequestDoesNotPublishMessage() {
        LiteratureSearchTaskPublisher publisher = mock(LiteratureSearchTaskPublisher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LiteratureSearchTaskPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        service = new LiteratureSearchTaskService(tasks, provider, eventProvider(null));
        LiteratureSearchTask existing = task(LiteratureSearchTaskService.STATUS_PENDING);
        when(tasks.findByIdempotencyKey(any())).thenReturn(Optional.of(existing));

        service.createTask(USER_ID, new LiteratureSearchTaskRequest("hybrid RAG", 8, null, true, "req-1", null));

        verify(publisher, never()).publishTaskCreated(any());
    }

    @Test
    void requestCancelMovesNonTerminalTaskToCancelRequested() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(tasks.requestCancelIfActive(
                eq(TASK_ID), eq(USER_ID), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    task.setStatus(
                            LiteratureSearchTaskService.STATUS_CANCEL_REQUESTED);
                    task.setCurrentStage("CANCEL_REQUESTED");
                    task.setCancelReason("user stopped");
                    return 1;
                });

        LiteratureSearchTask cancelled = service.requestCancel(USER_ID, TASK_ID, "user stopped");

        assertThat(cancelled.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_CANCEL_REQUESTED);
        assertThat(cancelled.getCurrentStage()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelled.getCancelReason()).isEqualTo("user stopped");
    }

    @Test
    void requestCancelTerminalTaskIsIdempotent() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_COMPLETED);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        LiteratureSearchTask result = service.requestCancel(USER_ID, TASK_ID, "too late");

        assertThat(result.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_COMPLETED);
        verify(tasks, never()).save(any());
    }

    @Test
    void claimForRunMovesPendingTaskToRunning() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_PENDING);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<LiteratureSearchTask> result = service.claimForRun(TASK_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_RUNNING);
        assertThat(result.get().getCurrentStage()).isEqualTo("SEARCHING");
        assertThat(result.get().getStartedAt()).isNotNull();
    }

    @Test
    void saveResultRespectsCancellationRace() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_CANCEL_REQUESTED);
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LiteratureSearchTask result = service.saveResult(USER_ID, TASK_ID, "{}", 1, 1, 1, "[]");

        assertThat(result.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_CANCELLED);
        assertThat(result.getResultJson()).isNull();
    }

    @Test
    void saveResultDoesNotOverwriteTimedOutFailedTask() {
        LiteratureSearchTask task = task(LiteratureSearchTaskService.STATUS_FAILED);
        task.setCurrentStage("TIMEOUT");
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.of(task));

        LiteratureSearchTask result = service.saveResult(USER_ID, TASK_ID, "{}", 1, 1, 1, "[]");

        assertThat(result.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_FAILED);
        assertThat(result.getCurrentStage()).isEqualTo("TIMEOUT");
        assertThat(result.getResultJson()).isNull();
        verify(tasks, never()).save(any());
    }

    @Test
    void scanStalledTasksRequeuesOldPendingTasks() {
        LiteratureSearchTaskPublisher publisher = mock(LiteratureSearchTaskPublisher.class);
        AgentTaskEventRecorder eventRecorder = mock(AgentTaskEventRecorder.class);
        service = new LiteratureSearchTaskService(tasks, publisherProvider(publisher), eventProvider(eventRecorder));
        LiteratureSearchTask pending = task(LiteratureSearchTaskService.STATUS_PENDING);
        when(tasks.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(LiteratureSearchTaskService.STATUS_PENDING),
                any(Instant.class),
                eq(PageRequest.of(0, 10))))
                .thenReturn(List.of(pending));
        when(tasks.findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
                eq(LiteratureSearchTaskService.STATUS_RUNNING),
                any(Instant.class),
                eq(PageRequest.of(0, 10))))
                .thenReturn(List.of());
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LiteratureSearchTaskService.ScanResult result =
                service.scanStalledTasks(Duration.ofMinutes(5), Duration.ofMinutes(30), 10);

        assertThat(result.requeuedPendingCount()).isEqualTo(1);
        assertThat(result.timedOutRunningCount()).isZero();
        assertThat(pending.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_PENDING);
        assertThat(pending.getCurrentStage()).isEqualTo("REQUEUED");
        verify(publisher).publishTaskCreated(pending);
        verify(eventRecorder).recordSafely(argThat(event ->
                event.eventType().equals("TASK_REQUEUED")
                        && event.stage().equals("REQUEUED")
                        && event.status().equals(LiteratureSearchTaskService.STATUS_PENDING)
        ));
    }

    @Test
    void scanStalledTasksMarksOldRunningTasksFailed() {
        LiteratureSearchTaskPublisher publisher = mock(LiteratureSearchTaskPublisher.class);
        AgentTaskEventRecorder eventRecorder = mock(AgentTaskEventRecorder.class);
        service = new LiteratureSearchTaskService(tasks, publisherProvider(publisher), eventProvider(eventRecorder));
        LiteratureSearchTask running = task(LiteratureSearchTaskService.STATUS_RUNNING);
        when(tasks.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(LiteratureSearchTaskService.STATUS_PENDING),
                any(Instant.class),
                eq(PageRequest.of(0, 10))))
                .thenReturn(List.of());
        when(tasks.findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
                eq(LiteratureSearchTaskService.STATUS_RUNNING),
                any(Instant.class),
                eq(PageRequest.of(0, 10))))
                .thenReturn(List.of(running));
        when(tasks.save(any(LiteratureSearchTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LiteratureSearchTaskService.ScanResult result =
                service.scanStalledTasks(Duration.ofMinutes(5), Duration.ofMinutes(30), 10);

        assertThat(result.requeuedPendingCount()).isZero();
        assertThat(result.timedOutRunningCount()).isEqualTo(1);
        assertThat(running.getStatus()).isEqualTo(LiteratureSearchTaskService.STATUS_FAILED);
        assertThat(running.getCurrentStage()).isEqualTo("TIMEOUT");
        assertThat(running.getErrorMessage()).contains("超时");
        assertThat(running.getFinishedAt()).isNotNull();
        verify(publisher, never()).publishTaskCreated(any());
        verify(eventRecorder).recordSafely(argThat(event ->
                event.eventType().equals("TASK_TIMED_OUT")
                        && event.stage().equals("TIMEOUT")
                        && event.status().equals(LiteratureSearchTaskService.STATUS_FAILED)
        ));
    }

    @Test
    void scanStalledTasksDoesNothingWhenNoPendingOrRunningMatches() {
        LiteratureSearchTaskPublisher publisher = mock(LiteratureSearchTaskPublisher.class);
        service = new LiteratureSearchTaskService(tasks, publisherProvider(publisher), eventProvider(null));
        when(tasks.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(LiteratureSearchTaskService.STATUS_PENDING),
                any(Instant.class),
                eq(PageRequest.of(0, 50))))
                .thenReturn(List.of());
        when(tasks.findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
                eq(LiteratureSearchTaskService.STATUS_RUNNING),
                any(Instant.class),
                eq(PageRequest.of(0, 50))))
                .thenReturn(List.of());

        LiteratureSearchTaskService.ScanResult result =
                service.scanStalledTasks(Duration.ofMinutes(5), Duration.ofMinutes(30), 50);

        assertThat(result.hasWork()).isFalse();
        verify(tasks, never()).save(any());
        verify(publisher, never()).publishTaskCreated(any());
    }

    @Test
    void inaccessibleTaskThrowsNotFound() {
        when(tasks.findByIdAndUserId(TASK_ID, USER_ID)).thenReturn(Optional.empty());

        try {
            service.getTask(USER_ID, TASK_ID);
        } catch (ResponseStatusException ex) {
            assertThat(ex.getReason()).contains("不可访问");
            return;
        }
        throw new AssertionError("Expected ResponseStatusException");
    }

    private LiteratureSearchTask task(String status) {
        LiteratureSearchTask task = new LiteratureSearchTask(
                USER_ID,
                null,
                "hybrid RAG",
                "hybrid rag",
                8,
                null,
                true,
                status,
                "QUEUED",
                "req-1",
                "idem"
        );
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        return task;
    }

    private ObjectProvider<LiteratureSearchTaskPublisher> publisherProvider(LiteratureSearchTaskPublisher publisher) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LiteratureSearchTaskPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        return provider;
    }

    private ObjectProvider<AgentTaskEventRecorder> eventProvider(AgentTaskEventRecorder eventRecorder) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentTaskEventRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventRecorder);
        return provider;
    }
}

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:literature_task_transitions;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@ContextConfiguration(
        classes = LiteratureSearchTaskServiceH2ConcurrencyTest.TestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiteratureSearchTaskServiceH2ConcurrencyTest {
    private static final Long USER_ID = 11L;

    @Autowired
    LiteratureSearchTaskRepository tasks;
    LiteratureSearchTaskService service;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    EntityManager entityManager;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = LiteratureSearchTask.class)
    @EnableJpaRepositories(
            basePackageClasses = LiteratureSearchTaskRepository.class)
    static class TestConfig {
    }

    @BeforeEach
    void clean() {
        tasks.deleteAll();
        service = new LiteratureSearchTaskService(tasks, null, null);
    }

    @Test
    void committedCompletionCannotBeOverwrittenByStaleCancel()
            throws Exception {
        LiteratureSearchTask task = runningTask("completion-wins");
        var completionWritten = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var completion = pool.submit(() -> transaction(() -> {
                lock(task.getId());
                LiteratureSearchTask result = service.saveResult(
                        USER_ID, task.getId(), "{\"items\":[]}",
                        1, 1, 2, "[]");
                completionWritten.countDown();
                await(releaseCompletion);
                return result;
            }));
            assertThat(completionWritten.await(5, TimeUnit.SECONDS)).isTrue();
            var staleCancelStarted = new CountDownLatch(1);
            var staleCancel = pool.submit(() -> {
                staleCancelStarted.countDown();
                return transaction(() -> service.requestCancel(
                        USER_ID, task.getId(), "too late"));
            });
            assertThat(staleCancelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);

            releaseCompletion.countDown();
            completion.get(5, TimeUnit.SECONDS);
            staleCancel.get(5, TimeUnit.SECONDS);

            LiteratureSearchTask stored = tasks.findById(task.getId())
                    .orElseThrow();
            assertThat(stored.getStatus())
                    .isEqualTo(LiteratureSearchTaskService.STATUS_COMPLETED);
            assertThat(stored.getResultJson()).isEqualTo("{\"items\":[]}");
            assertThat(stored.getCancelReason()).isNull();
        } finally {
            releaseCompletion.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void committedCancelCannotBeOverwrittenByStaleCompletion()
            throws Exception {
        LiteratureSearchTask task = runningTask("cancel-wins");
        var cancelWritten = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancellation = pool.submit(() -> transaction(() -> {
                lock(task.getId());
                LiteratureSearchTask result = service.requestCancel(
                        USER_ID, task.getId(), "stop now");
                cancelWritten.countDown();
                await(releaseCancel);
                return result;
            }));
            assertThat(cancelWritten.await(5, TimeUnit.SECONDS)).isTrue();
            var staleCompletionStarted = new CountDownLatch(1);
            var staleCompletion = pool.submit(() -> {
                staleCompletionStarted.countDown();
                return transaction(() -> service.saveResult(
                        USER_ID, task.getId(),
                        "{\"items\":[{\"title\":\"stale\"}]}",
                        1, 1, 2, "[]"));
            });
            assertThat(staleCompletionStarted.await(
                    5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);

            releaseCancel.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            staleCompletion.get(5, TimeUnit.SECONDS);

            LiteratureSearchTask stored = tasks.findById(task.getId())
                    .orElseThrow();
            assertThat(stored.getStatus())
                    .isEqualTo(LiteratureSearchTaskService.STATUS_CANCELLED);
            assertThat(stored.getResultJson()).isNull();
            assertThat(stored.getCancelReason()).isEqualTo("stop now");
        } finally {
            releaseCancel.countDown();
            pool.shutdownNow();
        }
    }

    private LiteratureSearchTask runningTask(String suffix) {
        return tasks.saveAndFlush(new LiteratureSearchTask(
                USER_ID, null, "hybrid RAG", "hybrid rag", 8, null, true,
                LiteratureSearchTaskService.STATUS_RUNNING, "SEARCHING",
                "request-" + suffix, "idem-" + suffix));
    }

    private void lock(Long taskId) {
        entityManager.find(
                LiteratureSearchTask.class, taskId,
                LockModeType.PESSIMISTIC_WRITE);
    }

    private <T> T transaction(java.util.concurrent.Callable<T> work) {
        return new TransactionTemplate(transactions).execute(status -> {
            try {
                return work.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for interleaving");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
