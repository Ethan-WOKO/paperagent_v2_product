package com.yanban.api.agent.v2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.paperagent.v2.persistence.PersistenceErrorCode;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import java.lang.reflect.Modifier;
import java.util.List;

class ProductExecutionStartRecoveryRepositoryAdapterContextTest {
    @Test
    void repositoryCanReceiveTheClassBasedProxyUsedByTheApplication() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var adapter = context.getBean(
                    ProductExecutionStartRecoveryRepositoryAdapter.class);

            assertTrue(AopUtils.isCglibProxy(adapter));
            assertEquals(PersistenceErrorCode.INVALID_ARGUMENT,
                    adapter.inspect(null).failure().orElseThrow().code());
        }
        for (Class<?> adapterType : List.of(
                ProductExecutionStartRecoveryRepositoryAdapter.class,
                ProductActiveStepReplanRepositoryAdapter.class,
                ProductEffectIntentRepositoryAdapter.class,
                ProductEffectOutcomeRepositoryAdapter.class,
                ProductExecutionStartRepositoryAdapter.class,
                ProductPlanExecutionContextRepositoryAdapter.class,
                ProductReceiptRepositoryAdapter.class,
                ProductStepActivationRepositoryAdapter.class,
                ProductStepCompletionRepositoryAdapter.class,
                ProductStepInterruptionRepositoryAdapter.class,
                ProductStepRecoveryRepositoryAdapter.class)) {
            assertFalse(Modifier.isFinal(adapterType.getModifiers()),
                    adapterType.getSimpleName());
        }
    }

    @Configuration
    static class Config {
        @Bean
        static DefaultAdvisorAutoProxyCreator classProxyCreator() {
            var creator = new DefaultAdvisorAutoProxyCreator();
            creator.setProxyTargetClass(true);
            return creator;
        }

        @Bean
        Advisor repositoryAdvisor() {
            return new DefaultPointcutAdvisor(
                    new AnnotationMatchingPointcut(
                            Repository.class, true),
                    (MethodInterceptor) invocation -> invocation.proceed());
        }

        @Bean
        ProductExecutionStartRecoveryTransactions transactions() {
            return mock(ProductExecutionStartRecoveryTransactions.class);
        }

        @Bean
        ProductExecutionStartRecoveryRepositoryAdapter adapter(
                ProductExecutionStartRecoveryTransactions transactions) {
            return new ProductExecutionStartRecoveryRepositoryAdapter(
                    transactions);
        }
    }
}
