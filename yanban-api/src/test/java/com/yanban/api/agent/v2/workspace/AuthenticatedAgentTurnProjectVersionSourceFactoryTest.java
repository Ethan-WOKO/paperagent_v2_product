package com.yanban.api.agent.v2.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.workspace.ProjectVersionSource;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthenticatedAgentTurnProjectVersionSourceFactoryTest {

    private static final long USER_ID = 7L;
    private static final long TURN_ID = 31L;
    private static final long PROJECT_ID = 42L;
    private static final String VERSION = "a".repeat(64);

    private final AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final AuthenticatedAgentTurnProjectVersionSourceFactory factory =
            new AuthenticatedAgentTurnProjectVersionSourceFactory(contexts, projects);

    @Test
    void resolvesOwnerQualifiedTurnBeforeCreatingBoundSource() {
        when(contexts.resolve(USER_ID, TURN_ID)).thenReturn(projectContext());

        ProjectVersionSource source = factory.create(USER_ID, TURN_ID);

        assertThat(source).isInstanceOf(ProductProjectVersionSource.class);
        verify(contexts).resolve(USER_ID, TURN_ID);
        verifyNoInteractions(projects);
    }

    @Test
    void exposesOnlyAuthenticatedFactoryAsPublicProductEntry() {
        assertThat(Modifier.isPublic(AuthenticatedAgentTurnProjectVersionSourceFactory.class.getModifiers()))
                .isTrue();
        assertThat(Modifier.isPublic(ProductProjectVersionSource.class.getModifiers())).isFalse();
        assertThat(ProductProjectVersionSource.class.getDeclaredConstructors())
                .allSatisfy(constructor ->
                        assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse());
    }

    @Test
    void propagatesResolutionFailureUnchangedWithoutProjectAccess() {
        RuntimeException failure = new IllegalStateException("owner-qualified rejection");
        when(contexts.resolve(USER_ID, TURN_ID)).thenThrow(failure);

        assertThatThrownBy(() -> factory.create(USER_ID, TURN_ID)).isSameAs(failure);

        verify(contexts).resolve(USER_ID, TURN_ID);
        verifyNoInteractions(projects);
    }

    @Test
    void rejectsWorkspaceTurnWithStableProductCodeAndPathBeforeProjectAccess() {
        VerifiedAgentTurnProductContext workspace = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", String.valueOf(TURN_ID), USER_ID, 13L, null),
                Optional.empty());
        when(contexts.resolve(USER_ID, TURN_ID)).thenReturn(workspace);

        assertThatThrownBy(() -> factory.create(USER_ID, TURN_ID))
                .isInstanceOfSatisfying(
                        AuthenticatedProjectVersionSourceBindingException.class,
                        failure -> {
                            assertThat(failure.code())
                                    .isEqualTo(AuthenticatedProjectVersionSourceBindingCode.PROJECT_SOURCE_REQUIRED);
                            assertThat(failure.path()).isEqualTo("agentTurn.projectVersion");
                        });

        verify(contexts).resolve(USER_ID, TURN_ID);
        verify(projects, never()).manifest(USER_ID, PROJECT_ID);
        verifyNoInteractions(projects);
    }

    @Test
    void rejectsInternallyCrossBoundProjectContextBeforeProjectAccess() {
        VerifiedAgentTurnProductContext malformed = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", String.valueOf(TURN_ID), USER_ID, 13L, null),
                Optional.of(VERSION));
        when(contexts.resolve(USER_ID, TURN_ID)).thenReturn(malformed);

        assertThatThrownBy(() -> factory.create(USER_ID, TURN_ID))
                .isInstanceOf(AuthenticatedProjectVersionSourceBindingException.class);
        verifyNoInteractions(projects);
    }

    private static VerifiedAgentTurnProductContext projectContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN",
                        String.valueOf(TURN_ID),
                        USER_ID,
                        13L,
                        PROJECT_ID),
                Optional.of(VERSION));
    }
}
