package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class ProductChainRepositoryProxyabilityTest {

    @Test
    void repositoryAdaptersUsedByConcreteTypeSupportSpringClassProxies() {
        ProductChainTransactions transactions = mock(
                ProductChainTransactions.class);

        assertDoesNotThrow(() -> classProxy(
                new ProductChainValidationRepositoryAdapter(transactions)));
        assertDoesNotThrow(() -> classProxy(
                new ProductChainValidationBundleRepositoryAdapter(
                        transactions)));
        assertDoesNotThrow(() -> classProxy(
                new ProductChainCandidateMaterializationFailureRepositoryAdapter(
                        transactions)));
    }

    private static Object classProxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        return factory.getProxy();
    }
}
