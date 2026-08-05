package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BrokerPropertiesTest {
    @Test void localAcceptanceIsExplicitWindowsLoopbackOnly() {
        BrokerProperties value = new BrokerProperties();
        value.setEnabled(true);
        value.setMode(BrokerProperties.Mode.LOCAL_ACCEPTANCE);
        value.setBindAddress("127.0.0.1");
        value.setRemoteAccess(false);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        assertThat(value.isProcessIdentitySafe()).isEqualTo(windows);
        value.setBindAddress("0.0.0.0");
        assertThat(value.isProcessIdentitySafe()).isFalse();
        value.setBindAddress("127.0.0.1");
        value.setRemoteAccess(true);
        assertThat(value.isProcessIdentitySafe()).isFalse();
    }

    @Test void e2bRequiresKeyPinnedTemplateAndAbsoluteHelperExecutables() {
        BrokerProperties value = new BrokerProperties();
        value.setEnabled(true);
        value.setE2bApiKey("server-side-key-that-is-long-enough");
        value.setE2bTemplate("yanban-research-v1");
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        value.setE2bPythonExecutable(root.resolve("python3").toString());
        value.setE2bHelper(root.resolve("e2b_provider.py").toString());

        assertThat(value.isE2bConfigurationSafe()).isTrue();
        value.setE2bTemplate("unsafe template; rm -rf");
        assertThat(value.isE2bConfigurationSafe()).isFalse();
    }
}
