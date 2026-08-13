package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedCommandSource;
import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedPlannerProgression;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoveryCoordinator;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production composition for the one durable product progression driver. */
@Configuration
@ConditionalOnProperty(
        prefix = "yanban.agent.v2.product", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ProductChainProgressionProperties.class)
public class ProductChainProgressionConfiguration {
    @Bean
    @ConditionalOnMissingBean(
            ProductChainTaskProgressionAdapter.ModelProgression.class)
    ProductChainTaskProgressionAdapter.ModelProgression
            missingProductChainModelProgressionOwner() {
        return command -> {
            throw new IllegalStateException(
                    "CHAIN_MODEL_PROGRESSION_OWNER_MISSING: "
                            + command.directive().role().name() + "/"
                            + command.directive().workState().name());
        };
    }

    @Bean
    ProductChainTaskProgressionAdapter productChainTaskProgression(
            ProductChainRecoveryCoordinator recovery,
            ProductChainTaskProgressionAdapter.ModelProgression models,
            ProductChainMechanicalProgression mechanical,
            ProductChainMechanicalProposalProgression proposals) {
        return new ProductChainTaskProgressionAdapter(
                recovery, models, mechanical, proposals, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    ProductChainClaimLeaseKeeper productChainClaimLeaseKeeper(
            ProductChainProgressionClaimStore claims) {
        return new ProductChainClaimLeaseKeeper(claims, Clock.systemUTC());
    }

    @Bean
    ProductChainDurableProgressionDriver productChainProgressionDriver(
            ProductChainReceivedCommandSource received,
            ProductChainProgressionClaimStore claims,
            ProductChainReceivedPlannerProgression receivedProgression,
            ProductChainTaskProgressionAdapter taskProgression,
            ProductChainClaimLeaseKeeper leaseKeeper) {
        return new ProductChainDurableProgressionDriver(
                received, claims,
                (command, claim) -> receivedProgression.advance(command),
                taskProgression,
                taskId -> UUID.randomUUID().toString(),
                leaseKeeper,
                Clock.systemUTC());
    }

    @Bean
    ProductChainProgressionWakeup productChainProgressionWakeup(
            ProductChainDurableProgressionDriver driver,
            ProductChainProgressionProperties properties) {
        return new ProductChainProgressionWakeup(
                driver, properties,
                "yanban-api:" + UUID.randomUUID());
    }
}
