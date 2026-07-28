package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.persistence.StepCompletionRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.ActiveStepReplanRepository;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.composition.DefaultActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.materialization.DeterministicActiveStepCompletionMaterializer;
import io.paperagent.v2.runtime.execution.progression.DefaultStepProgressionInspector;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanComposer;
import io.paperagent.v2.runtime.execution.replan.composition.DefaultBoundedStepReplanComposer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Product-side wiring for the stable Runtime progression boundaries. */
@Configuration
public class ProductStepProgressionConfiguration {
    @Bean
    StepProgressionInspector stepProgressionInspector(
            StepRecoveryRepository repository) {
        return new DefaultStepProgressionInspector(repository);
    }

    @Bean
    ActiveStepCompletionComposer activeStepCompletionComposer(
            StepCompletionRepository repository) {
        return new DefaultActiveStepCompletionComposer(
                new DeterministicActiveStepCompletionMaterializer(),
                repository);
    }

    @Bean
    BoundedStepReplanComposer boundedStepReplanComposer(
            ActiveStepReplanRepository repository) {
        return new DefaultBoundedStepReplanComposer(repository);
    }
}
