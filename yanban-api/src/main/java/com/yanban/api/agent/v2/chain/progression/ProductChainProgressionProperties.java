package com.yanban.api.agent.v2.chain.progression;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operational limits for the single durable product progression driver. */
@ConfigurationProperties(prefix = "yanban.agent.v2.progression")
public class ProductChainProgressionProperties {
    private boolean enabled = true;
    private Duration claimLifetime = Duration.ofMinutes(2);
    private int receivedPageSize = 25;
    private int committedTaskPageSize = 25;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getClaimLifetime() {
        return claimLifetime;
    }

    public void setClaimLifetime(Duration claimLifetime) {
        this.claimLifetime = claimLifetime;
    }

    public int getReceivedPageSize() {
        return receivedPageSize;
    }

    public void setReceivedPageSize(int receivedPageSize) {
        this.receivedPageSize = receivedPageSize;
    }

    public int getCommittedTaskPageSize() {
        return committedTaskPageSize;
    }

    public void setCommittedTaskPageSize(int committedTaskPageSize) {
        this.committedTaskPageSize = committedTaskPageSize;
    }

    ProductChainDurableProgressionDriver.TickRequest request(
            String ownerId) {
        return new ProductChainDurableProgressionDriver.TickRequest(
                ownerId, claimLifetime, receivedPageSize,
                committedTaskPageSize);
    }
}
