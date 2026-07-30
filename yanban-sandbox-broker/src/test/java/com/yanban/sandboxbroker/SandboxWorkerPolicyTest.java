package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SandboxWorkerPolicyTest {
    private final BrokerProperties properties=new BrokerProperties();
    private final SandboxWorker worker=new SandboxWorker(mock(SandboxLeaseService.class),properties,new ObjectMapper(),new SandboxProcessRegistry(),new ProviderEnvironment(properties),new SandboxProviderCommandFactory(properties));
    @Test void acceptsOnlyExactActiveLocalDenyAllAndExactSandboxName(){
        String valid="[{\"source\":\"local\",\"decision\":\"deny\",\"type\":\"network\",\"active\":true,\"resource\":\"**\"}]";
        ReflectionTestUtils.invokeMethod(worker,"requireDenyAll",valid);
        String current="{\"rules\":[{\"origin\":\"scoped\",\"decision\":\"deny\",\"resource_type\":\"network\",\"status\":\"active\",\"resources\":[\"**\"]}]}";
        ReflectionTestUtils.invokeMethod(worker,"requireDenyAll",current);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDenyAll",valid.replace("true","false"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDenyAll",current.replace("active","inactive"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDenyAll",current.replace("scoped","kit"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDenyAll",current.replace("[\"**\"]","[\"example.com\"]"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDenyAll","{\"note\":\"**\"}")).isInstanceOf(Exception.class);
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(worker,"sandboxExists","[{\"name\":\"yb-123\"}]","yb-123")).isTrue();
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(worker,"sandboxExists","[{\"name\":\"yb-1234\"}]","yb-123")).isFalse();
    }

    @Test void acceptsOnlyTheExplicitDependencyNetworkPermission(){
        String valid="{\"origin\":\"scoped\",\"decision\":\"allow\",\"resource_type\":\"network\","
                +"\"status\":\"active\",\"resources\":[\"**\"]}";
        ReflectionTestUtils.invokeMethod(worker,"requireDependencyNetwork",valid);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDependencyNetwork",
                valid.replace("**","example.com"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"requireDependencyNetwork",
                valid.replace("\"allow\"","\"deny\""))).isInstanceOf(Exception.class);
    }
}
