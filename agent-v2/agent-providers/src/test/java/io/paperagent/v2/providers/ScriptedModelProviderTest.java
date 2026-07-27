package io.paperagent.v2.providers;

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

class ScriptedModelProviderTest {
    @Test
    void exactOrderedTurnsConsumeOnceAndExposeImmutableObservations() {
        ModelRequest firstRequest = ProviderFixtures.request("request-1");
        ModelRequest secondRequest = ProviderFixtures.request("request-2");
        ModelResponse firstResponse = ProviderFixtures.textResponse("first");
        ModelResponse secondResponse = ProviderFixtures.textResponse("second");
        ScriptedModelProvider provider = new ScriptedModelProvider(List.of(
                new ScriptedModelProvider.ScriptStep(firstRequest, firstResponse),
                new ScriptedModelProvider.ScriptStep(secondRequest, secondResponse)));

        assertEquals(firstResponse, provider.complete(firstRequest));
        assertEquals(secondResponse, provider.complete(secondRequest));
        provider.assertFullyConsumed();
        assertEquals(2, provider.consumedCount());
        assertEquals(0, provider.remainingCount());
        assertEquals(
                List.of("request-1", "request-2"),
                provider.observations().stream()
                        .map(observation -> observation.request().requestId().value())
                        .toList());
        assertTrue(provider.observations().stream().allMatch(ScriptObservation::consumed));
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.observations().clear());
    }

    @Test
    void mismatchOutOfOrderAndExhaustionDoNotConsumeWrongEntries() {
        ModelRequest firstRequest = ProviderFixtures.request("request-first");
        ModelRequest secondRequest = ProviderFixtures.request("request-second");
        ScriptedModelProvider provider = new ScriptedModelProvider(List.of(
                new ScriptedModelProvider.ScriptStep(
                        firstRequest,
                        ProviderFixtures.textResponse("first")),
                new ScriptedModelProvider.ScriptStep(
                        secondRequest,
                        ProviderFixtures.textResponse("second"))));

        assertFailure(
                ProviderFailureCode.SCRIPTED_MISMATCH,
                provider.complete(ProviderFixtures.request("request-unexpected")));
        assertFailure(
                ProviderFailureCode.SCRIPTED_OUT_OF_ORDER,
                provider.complete(secondRequest));
        assertEquals(0, provider.consumedCount());

        assertInstanceOf(ModelResponse.class, provider.complete(firstRequest));
        assertInstanceOf(ModelResponse.class, provider.complete(secondRequest));
        assertFailure(
                ProviderFailureCode.SCRIPTED_EXHAUSTED,
                provider.complete(firstRequest));
        assertEquals(2, provider.consumedCount());
        assertEquals(List.of(false, false, true, true, false),
                provider.observations().stream().map(ScriptObservation::consumed).toList());
    }

    @Test
    void unconsumedScriptAssertionHasStableFailureCode() {
        ScriptedModelProvider provider = new ScriptedModelProvider(List.of(
                new ScriptedModelProvider.ScriptStep(
                        ProviderFixtures.request("request-remaining"),
                        ProviderFixtures.textResponse("response"))));

        ScriptedProviderAssertionException exception = assertThrows(
                ScriptedProviderAssertionException.class,
                provider::assertFullyConsumed);
        assertEquals(
                ProviderFailureCode.SCRIPTED_UNCONSUMED,
                exception.failure().code());
        assertEquals("1", exception.failure().details().get("remainingCount"));
    }

    @Test
    void preCancelledRequestIsDeterministicAndDoesNotConsumeScript() {
        ModelRequest expected = ProviderFixtures.request("request-cancel");
        ModelRequest cancelled = ProviderFixtures.request("request-cancel", true);
        ScriptedModelProvider provider = new ScriptedModelProvider(List.of(
                new ScriptedModelProvider.ScriptStep(
                        expected,
                        ProviderFixtures.textResponse("not cancelled"))));

        assertFailure(
                ProviderFailureCode.CANCELLED,
                provider.complete(cancelled));
        assertEquals(0, provider.consumedCount());
        assertInstanceOf(ModelResponse.class, provider.complete(expected));
        provider.assertFullyConsumed();
    }

    @Test
    void scriptedFailuresPreserveEveryProviderFailureCategory() {
        for (ProviderFailureCode code : ProviderFailureCode.values()) {
            ModelRequest request = ProviderFixtures.request("request-" + code.name());
            ProviderFailure expected = ProviderFixtures.failure(code);
            ScriptedModelProvider provider = new ScriptedModelProvider(List.of(
                    new ScriptedModelProvider.ScriptStep(request, expected)));

            assertEquals(expected, provider.complete(request));
            provider.assertFullyConsumed();
        }
    }

    @Test
    void concurrentCallsConsumeEachEntryAtMostOnceWithoutSleeps() throws Exception {
        int callCount = 24;
        ModelRequest sharedRequest = ProviderFixtures.request("request-concurrent");
        List<ScriptedModelProvider.ScriptStep> script = new ArrayList<>();
        for (int index = 0; index < callCount; index++) {
            script.add(new ScriptedModelProvider.ScriptStep(
                    sharedRequest,
                    ProviderFixtures.textResponse("response-" + index)));
        }
        ScriptedModelProvider provider = new ScriptedModelProvider(script);
        CountDownLatch ready = new CountDownLatch(callCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callCount);
        try {
            List<Future<ModelProviderResult>> futures = new ArrayList<>();
            for (int index = 0; index < callCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return provider.complete(sharedRequest);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Set<String> texts = new HashSet<>();
            for (Future<ModelProviderResult> future : futures) {
                ModelResponse response = assertInstanceOf(
                        ModelResponse.class,
                        future.get(5, TimeUnit.SECONDS));
                texts.add(response.assistantText().orElseThrow());
            }

            assertEquals(callCount, texts.size());
            assertEquals(callCount, provider.consumedCount());
            assertEquals(0, provider.remainingCount());
            assertEquals(callCount, provider.observations().size());
            assertEquals(
                    callCount,
                    provider.observations().stream()
                            .map(ScriptObservation::attemptSequence)
                            .distinct()
                            .count());
            assertTrue(provider.observations().stream().allMatch(ScriptObservation::consumed));
            provider.assertFullyConsumed();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static ProviderFailure assertFailure(
            ProviderFailureCode expectedCode,
            ModelProviderResult result) {
        ProviderFailure failure = assertInstanceOf(ProviderFailure.class, result);
        assertEquals(expectedCode, failure.code());
        return failure;
    }
}
