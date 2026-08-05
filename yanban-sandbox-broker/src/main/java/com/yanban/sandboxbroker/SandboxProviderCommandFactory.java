package com.yanban.sandboxbroker;

import org.springframework.stereotype.Component;

@Component
final class SandboxProviderCommandFactory {
    private final BrokerProperties properties;

    SandboxProviderCommandFactory(BrokerProperties properties) { this.properties = properties; }

    SandboxProviderCommands commands() {
        return new E2bCommandFactory(properties.getE2bPythonExecutable(), properties.getE2bHelper(),
                properties.getE2bTemplate());
    }
}
