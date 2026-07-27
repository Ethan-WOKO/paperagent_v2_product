package io.paperagent.v2.sandbox;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedSandboxPortTest {
    @Test
    void exactOrderedSuccessAndFailureConsumeOnce() {
        SandboxRequest firstRequest = SandboxFixtures.request("request-first");
        SandboxRequest secondRequest = SandboxFixtures.request("request-second");
        ExecutedCommand firstResult = SandboxFixtures.executed(0, "ok");
        SandboxFailure secondResult = SandboxFixtures.failure(
                SandboxFailureCode.UNSUPPORTED_PROFILE);
        ScriptedSandboxPort port = new ScriptedSandboxPort(List.of(
                new ScriptedSandboxPort.ScriptStep(firstRequest, firstResult),
                new ScriptedSandboxPort.ScriptStep(secondRequest, secondResult)));

        assertEquals(firstResult, port.execute(firstRequest));
        assertEquals(secondResult, port.execute(secondRequest));
        port.assertFullyConsumed();
        assertEquals(2, port.consumedCount());
        assertEquals(0, port.remainingCount());
        assertEquals(
                List.of("request-first", "request-second"),
                port.observations().stream()
                        .map(observation -> observation.request().requestId().value())
                        .toList());
        assertTrue(port.observations().stream().allMatch(ScriptObservation::consumed));
        assertThrows(UnsupportedOperationException.class, () -> port.observations().clear());
    }

    @Test
    void mismatchOutOfOrderAndExhaustionDoNotConsumeWrongEntries() {
        SandboxRequest firstRequest = SandboxFixtures.request("request-first");
        SandboxRequest secondRequest = SandboxFixtures.request("request-second");
        ScriptedSandboxPort port = new ScriptedSandboxPort(List.of(
                new ScriptedSandboxPort.ScriptStep(
                        firstRequest,
                        SandboxFixtures.executed(0, "first")),
                new ScriptedSandboxPort.ScriptStep(
                        secondRequest,
                        SandboxFixtures.executed(0, "second"))));

        assertFailure(
                SandboxFailureCode.SCRIPTED_MISMATCH,
                port.execute(SandboxFixtures.request("request-unexpected")));
        assertFailure(
                SandboxFailureCode.SCRIPTED_OUT_OF_ORDER,
                port.execute(secondRequest));
        assertEquals(0, port.consumedCount());

        assertInstanceOf(ExecutedCommand.class, port.execute(firstRequest));
        assertInstanceOf(ExecutedCommand.class, port.execute(secondRequest));
        assertFailure(
                SandboxFailureCode.SCRIPTED_EXHAUSTED,
                port.execute(firstRequest));
        assertEquals(2, port.consumedCount());
        assertEquals(
                List.of(false, false, true, true, false),
                port.observations().stream().map(ScriptObservation::consumed).toList());
    }

    @Test
    void preCancelledCallDoesNotConsumeScript() {
        SandboxRequest expected = SandboxFixtures.request("request-cancel");
        SandboxRequest cancelled = SandboxFixtures.request("request-cancel", true);
        ScriptedSandboxPort port = new ScriptedSandboxPort(List.of(
                new ScriptedSandboxPort.ScriptStep(
                        expected,
                        SandboxFixtures.executed(0, "not cancelled"))));

        assertFailure(SandboxFailureCode.CANCELLED, port.execute(cancelled));
        assertEquals(0, port.consumedCount());
        assertInstanceOf(ExecutedCommand.class, port.execute(expected));
        port.assertFullyConsumed();
    }

    @Test
    void emptyArgumentAndEnvironmentValueParticipateInExactScriptMatching() {
        SandboxRequest expected = SandboxFixtures.request(
                "request-empty-values",
                List.of("tool", "", " "),
                java.util.Map.of("EMPTY", "", "SPACES", " "),
                java.util.Map.of(),
                SandboxOperationIntent.COMMAND,
                SandboxFixtures.profile(),
                false);
        ScriptedSandboxPort port = new ScriptedSandboxPort(List.of(
                new ScriptedSandboxPort.ScriptStep(
                        expected,
                        SandboxFixtures.executed(0, "matched"))));

        assertInstanceOf(ExecutedCommand.class, port.execute(expected));
        port.assertFullyConsumed();
        assertEquals(List.of("tool", "", " "), expected.argv());
        assertEquals("", expected.environment().get("EMPTY"));
    }

    @Test
    void unconsumedAssertionHasStableFailureCodeAndCount() {
        ScriptedSandboxPort port = new ScriptedSandboxPort(List.of(
                new ScriptedSandboxPort.ScriptStep(
                        SandboxFixtures.request("request-unconsumed"),
                        SandboxFixtures.executed(0, "result"))));

        ScriptedSandboxAssertionException exception = assertThrows(
                ScriptedSandboxAssertionException.class,
                port::assertFullyConsumed);

        assertEquals(SandboxFailureCode.SCRIPTED_UNCONSUMED, exception.failure().code());
        assertEquals("1", exception.failure().details().get("remainingCount"));
    }

    @Test
    void scriptedFailuresPreserveEveryFailureCategory() {
        for (SandboxFailureCode code : SandboxFailureCode.values()) {
            SandboxRequest request = SandboxFixtures.request("request-" + code.name());
            SandboxFailure expected = SandboxFixtures.failure(code);
            ScriptedSandboxPort port = new ScriptedSandboxPort(List.of(
                    new ScriptedSandboxPort.ScriptStep(request, expected)));

            assertEquals(expected, port.execute(request));
            port.assertFullyConsumed();
        }
    }

    @Test
    void executedOutcomeIsValidatedAgainstItsExpectedRequestProfile() {
        SandboxRequest request = SandboxFixtures.request(
                "request-invalid-script",
                List.of("tool"),
                java.util.Map.of(),
                java.util.Map.of(),
                SandboxOperationIntent.COMMAND,
                SandboxFixtures.profile(
                        Set.of(io.paperagent.v2.contracts.Capability.EXECUTE_COMMAND),
                        Set.of(),
                        2),
                false);

        SandboxValidationException exception = SandboxFixtures.violation(
                () -> new ScriptedSandboxPort.ScriptStep(
                        request,
                        SandboxFixtures.executed(0, "too-large")));
        assertEquals(SandboxValidationCode.OUTPUT_LIMIT_EXCEEDED, exception.code());
    }

    @Test
    void concurrentCallsConsumeEachScriptEntryAtMostOnceWithoutSleeps() throws Exception {
        int callCount = 24;
        SandboxRequest sharedRequest = SandboxFixtures.request("request-concurrent");
        List<ScriptedSandboxPort.ScriptStep> script = new ArrayList<>();
        for (int index = 0; index < callCount; index++) {
            script.add(new ScriptedSandboxPort.ScriptStep(
                    sharedRequest,
                    SandboxFixtures.executed(index, "result-" + index)));
        }
        ScriptedSandboxPort port = new ScriptedSandboxPort(script);
        CountDownLatch ready = new CountDownLatch(callCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callCount);
        try {
            List<Future<SandboxResult>> futures = new ArrayList<>();
            for (int index = 0; index < callCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return port.execute(sharedRequest);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Set<Integer> exitCodes = new HashSet<>();
            for (Future<SandboxResult> future : futures) {
                ExecutedCommand result = assertInstanceOf(
                        ExecutedCommand.class,
                        future.get(5, TimeUnit.SECONDS));
                exitCodes.add(result.exitCode());
            }

            assertEquals(callCount, exitCodes.size());
            assertEquals(callCount, port.consumedCount());
            assertEquals(0, port.remainingCount());
            assertEquals(callCount, port.observations().size());
            assertEquals(
                    callCount,
                    port.observations().stream()
                            .map(ScriptObservation::attemptSequence)
                            .distinct()
                            .count());
            assertTrue(port.observations().stream().allMatch(ScriptObservation::consumed));
            port.assertFullyConsumed();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static SandboxFailure assertFailure(
            SandboxFailureCode expectedCode,
            SandboxResult result) {
        SandboxFailure failure = assertInstanceOf(SandboxFailure.class, result);
        assertEquals(expectedCode, failure.code());
        return failure;
    }
}
