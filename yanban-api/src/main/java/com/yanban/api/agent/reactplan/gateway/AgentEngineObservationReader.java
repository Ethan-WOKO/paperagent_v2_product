package com.yanban.api.agent.reactplan.gateway;

import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
public class AgentEngineObservationReader {
    private final AgentEngineModelCompletionRepository models;

    AgentEngineObservationReader(AgentEngineModelCompletionRepository models) {
        this.models = models;
    }

    @Transactional(readOnly = true)
    public List<ModelFact> models(String taskId) {
        return models.findByTaskIdOrderByCreatedAtAsc(taskId).stream().map(value -> new ModelFact(
                value.clientRequestId(), value.providerKey(), value.modelName(), value.state(),
                value.createdAt().toInstant(ZoneOffset.UTC).toString(),
                value.updatedAt().toInstant(ZoneOffset.UTC).toString(),
                value.requestBytes(), value.responseBytes(), value.promptTokens(),
                value.completionTokens(), value.replayCount(), value.errorCode())).toList();
    }

    public record ModelFact(String callId, String provider, String model, String state,
                            String startedAt, String finishedAt, long requestBytes,
                            long responseBytes, int promptTokens, int completionTokens,
                            int replayCount, String errorCode) { }
}
