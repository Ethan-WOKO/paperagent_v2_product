package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.observability.ProjectChainSafeLogger;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainLogCanaryTest {
    @Test void ordinaryLogsNeverExposeRandomBodyCanary()
            throws ReflectiveOperationException {
        String canary = "chain-canary-" + UUID.randomUUID();
        List<String> calls = new ArrayList<>();
        Logger sink = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, arguments) -> {
                    calls.add(method.getName() + ":"
                            + Arrays.deepToString(arguments));
                    return method.getReturnType() == boolean.class
                            ? false : null;
                });
        var constructor = ProjectChainSafeLogger.class
                .getDeclaredConstructor(Logger.class);
        constructor.setAccessible(true);
        ProjectChainSafeLogger logger = constructor.newInstance(sink);
        ProjectChainSafeLogger.SafeEvent event = new ProjectChainSafeLogger.SafeEvent(
                "r", "c", "t", null, null, null, "p", "m", 1, 0, 0,
                null, canary, 1, null, null, null);
        ProjectChainSafeLogger.SensitiveBodies bodies =
                new ProjectChainSafeLogger.SensitiveBodies(
                        canary, canary, canary, canary,
                        canary, canary, canary, canary);

        logger.info(event, bodies);
        logger.error(event, canary, bodies,
                new IllegalStateException(canary));

        assertFalse(calls.isEmpty());
        assertTrue(calls.stream().noneMatch(value -> value.contains(canary)),
                "ordinary logs must not contain body or exception-message canaries");
        assertTrue(calls.stream().anyMatch(value -> value.contains("OTHER")),
                "unknown finish/error tokens must be normalized");
    }
}
