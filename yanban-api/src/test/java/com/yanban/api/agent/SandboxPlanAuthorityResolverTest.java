package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SandboxPlanAuthorityResolverTest {
    @Test void policyDigestIsStableAcrossCheckpointVersionsButBoundToAuthority() {
        ResolvedToolPolicy policy = new ResolvedToolPolicy(List.of("sandbox_execute"), 12, 1, "project");
        var ceiling = new AgentPlanCheckpointService.BudgetCeiling(240, 2, 1, 12);
        var context = new ProjectRuntimeContext(1L, 2L);
        var manifest = new ProjectManifestResponse(2L, "v".repeat(64), List.of());
        var first = new AgentPlanCheckpointService.Validation(false, 1L, context, manifest, ceiling);
        var recovered = new AgentPlanCheckpointService.Validation(true, 9L, context, manifest, ceiling);

        assertThat(SandboxPlanAuthorityResolver.policyDigest(policy, first))
                .isEqualTo(SandboxPlanAuthorityResolver.policyDigest(policy, recovered));
        assertThat(SandboxPlanAuthorityResolver.policyDigest(
                new ResolvedToolPolicy(List.of("project_read_file", "sandbox_execute"), 11, 1, "other_path"), recovered))
                .isEqualTo(SandboxPlanAuthorityResolver.policyDigest(policy, recovered));
        var reducedCeiling = new AgentPlanCheckpointService.BudgetCeiling(240, 2, 1, 11);
        var changedBudget = new AgentPlanCheckpointService.Validation(true, 9L, context, manifest, reducedCeiling);
        assertThat(SandboxPlanAuthorityResolver.policyDigest(policy, changedBudget))
                .isNotEqualTo(SandboxPlanAuthorityResolver.policyDigest(policy, recovered));
        assertThatThrownBy(() -> SandboxPlanAuthorityResolver.policyDigest(
                new ResolvedToolPolicy(List.of("project_read_file"), 12, 1, "revoked"), recovered))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revoked");
    }

    @Test void rejectsCrossPlanStepAndRevokedToolWithoutConstructibleAuthority() {
        Fixture fixture=new Fixture();
        ReflectionTestUtils.setField(fixture.step,"planId",99L);
        assertThatThrownBy(fixture::resolve).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(fixture.step,"planId",4L);
        fixture.policy=new ResolvedToolPolicy(List.of(),1,1,"revoked");
        assertThatThrownBy(fixture::resolve).isInstanceOf(IllegalStateException.class);
        assertThat(SandboxPlanAuthorityResolver.Resolution.class.getDeclaredConstructors()[0].canAccess(null)).isFalse();
    }

    @Test void databaseTimeLeaseLossAndBudgetExhaustionFailClosed() {
        Fixture fixture=new Fixture();
        doThrow(new IllegalStateException("expired")).when(fixture.leases).assertOwned(fixture.lease);
        assertThatThrownBy(fixture::resolve).isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");
        fixture=new Fixture();when(fixture.checkpoints.remainingToolCalls(any(),any())).thenReturn(0);
        assertThatThrownBy(fixture::resolve).isInstanceOf(IllegalStateException.class).hasMessageContaining("exhausted");
    }

    private static final class Fixture {
        final AgentPlanRunLeaseService leases=mock(AgentPlanRunLeaseService.class);final AgentPlanRepository plans=mock(AgentPlanRepository.class);
        final AgentPlanStepRepository steps=mock(AgentPlanStepRepository.class);final AgentPlanCheckpointService checkpoints=mock(AgentPlanCheckpointService.class);
        final AgentPlanExecutionLease lease=new AgentPlanExecutionLease(4L,1L,"o","t",7,LocalDateTime.now().plusMinutes(1),false);
        final AgentPlan plan=new AgentPlan(3L,1L,"g","s",false,null,"{}");final AgentPlanStep step=new AgentPlanStep(4L,"s",1,"t","d","SANDBOX_EXECUTE","[]","[\"sandbox_execute\"]","ok");
        ResolvedToolPolicy policy=new ResolvedToolPolicy(List.of("sandbox_execute"),1,1,"server");final SandboxPlanAuthorityResolver resolver;
        Fixture(){ReflectionTestUtils.setField(plan,"id",4L);plan.markRunning();ReflectionTestUtils.setField(step,"id",5L);
            when(plans.findByIdAndUserId(4L,1L)).thenReturn(Optional.of(plan));when(steps.findById(5L)).thenReturn(Optional.of(step));
            var ceiling=new AgentPlanCheckpointService.BudgetCeiling(100,2,1,2);var validation=new AgentPlanCheckpointService.Validation(false,1,new ProjectRuntimeContext(1L,2L),new ProjectManifestResponse(2L,"v".repeat(64),List.of()),ceiling);
            when(checkpoints.initializeOrValidate(any(),any(),any())).thenReturn(validation);when(checkpoints.remainingToolCalls(any(),any())).thenReturn(1);
            resolver=new SandboxPlanAuthorityResolver(leases,plans,steps,checkpoints,new ObjectMapper());}
        void resolve(){resolver.resolve(lease,5L,policy,new AgentPlanCheckpointService.BudgetCeiling(100,2,1,2));}
    }
}
