package com.yanban.api.agent.reactplan;

import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProjectPlanBootstrapConfigurationTest {
    @Test
    void reactBootstrapDoesNotRequireLegacyStepExecutionBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProjectPlanBootstrapConfiguration.class)
                .withBean(PlanBootstrapRepository.class, () -> mock(PlanBootstrapRepository.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PersistentPlanBootstrapper.class);
                    assertThat(context).doesNotHaveBean(FreshExecutionStarter.class);
                });
    }
}
