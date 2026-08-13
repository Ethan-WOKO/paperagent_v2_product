package com.yanban.api.agent.v2.chain.progression;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** A restart-safe wake-up hint for the durable database-backed driver. */
public final class ProductChainProgressionWakeup {
    private static final Logger log = LoggerFactory.getLogger(
            ProductChainProgressionWakeup.class);

    private final ProgressionTick progression;
    private final ProductChainProgressionProperties properties;
    private final ProductChainDurableProgressionDriver.TickRequest request;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean disabledReported = new AtomicBoolean();

    public ProductChainProgressionWakeup(
            ProductChainDurableProgressionDriver progression,
            ProductChainProgressionProperties properties,
            String ownerId) {
        this(progression::advance, properties, properties.request(ownerId));
        log.info("Agent V2 durable progression configured enabled={} "
                        + "claimLifetime={} receivedPageSize={} "
                        + "committedTaskPageSize={}",
                properties.isEnabled(), properties.getClaimLifetime(),
                properties.getReceivedPageSize(),
                properties.getCommittedTaskPageSize());
    }

    ProductChainProgressionWakeup(
            ProgressionTick progression,
            ProductChainProgressionProperties properties,
            ProductChainDurableProgressionDriver.TickRequest request) {
        this.progression = Objects.requireNonNull(
                progression, "progression");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.request = Objects.requireNonNull(request, "request");
    }

    @Scheduled(
            initialDelayString = "${yanban.agent.v2.progression.initial-delay-ms:5000}",
            fixedDelayString = "${yanban.agent.v2.progression.fixed-delay-ms:1000}")
    public void wake() {
        if (!properties.isEnabled()) {
            if (disabledReported.compareAndSet(false, true)) {
                log.warn("Agent V2 durable progression is disabled by "
                        + "runtime configuration");
            }
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            ProductChainDurableProgressionDriver.TickResult result =
                    progression.advance(request);
            if (!result.failures().isEmpty()) {
                log.warn("Agent V2 durable progression completed with "
                                + "failures scanned={} advanced={} skipped={} "
                                + "failures={}",
                        result.scanned(), result.advanced(), result.skipped(),
                        result.failures());
            } else if (result.advanced() > 0) {
                log.info("Agent V2 durable progression advanced work "
                                + "scanned={} advanced={} skipped={}",
                        result.scanned(), result.advanced(), result.skipped());
            }
        } catch (RuntimeException failure) {
            log.error("Agent V2 durable progression scan failed; durable "
                    + "authority remains available for a later wake-up", failure);
        } finally {
            running.set(false);
        }
    }

    @FunctionalInterface
    interface ProgressionTick {
        ProductChainDurableProgressionDriver.TickResult advance(
                ProductChainDurableProgressionDriver.TickRequest request);
    }
}
