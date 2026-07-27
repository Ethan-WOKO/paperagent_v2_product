package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.ViolationCode;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeValidationCode;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTaskFrameValidationTest {
    private final DeterministicProductTaskFrameAdapter adapter =
            new DeterministicProductTaskFrameAdapter();

    @Test
    void rejectsNullRequest() {
        assertProductFailure(
                ProductTaskFrameValidationCode.REQUIRED_VALUE_MISSING,
                "request",
                () -> adapter.bind(null));
    }

    @Test
    void rejectsNullIdentity() {
        assertProductFailure(
                ProductTaskFrameValidationCode.REQUIRED_VALUE_MISSING,
                "request.identity",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        null,
                        Optional.empty())));
    }

    @Test
    void productIdentityRejectsNullUserBeforeIntake() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProductTaskFrameTestFixtures.identity(null, null, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveUser(long userId) {
        assertProductFailure(
                ProductTaskFrameValidationCode.NON_POSITIVE_ID,
                "request.identity.userId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(userId, null, null),
                        Optional.empty())));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveOptionalSession(long sessionId) {
        assertProductFailure(
                ProductTaskFrameValidationCode.NON_POSITIVE_ID,
                "request.identity.sessionId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, sessionId, null),
                        Optional.empty())));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveProject(long projectId) {
        assertProductFailure(
                ProductTaskFrameValidationCode.NON_POSITIVE_ID,
                "request.identity.projectId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, null, projectId),
                        Optional.of("version-9"))));
    }

    @Test
    void rejectsNullProjectVersionOptional() {
        assertProductFailure(
                ProductTaskFrameValidationCode.REQUIRED_VALUE_MISSING,
                "request.projectVersionId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, null, null),
                        null)));
    }

    @Test
    void rejectsMissingProjectVersion() {
        assertProductFailure(
                ProductTaskFrameValidationCode.PROJECT_VERSION_MISSING,
                "request.projectVersionId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, null, 42L),
                        Optional.empty())));
    }

    @Test
    void rejectsUnexpectedProjectVersion() {
        assertProductFailure(
                ProductTaskFrameValidationCode.PROJECT_VERSION_UNEXPECTED,
                "request.projectVersionId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, null, null),
                        Optional.of("version-9"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankProjectVersion(String versionId) {
        assertProductFailure(
                ProductTaskFrameValidationCode.PROJECT_VERSION_BLANK,
                "request.projectVersionId",
                () -> adapter.bind(ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, null, 42L),
                        Optional.of(versionId))));
    }

    @Test
    void preservesTypedDirectRouteFailure() {
        ProductTaskFrameIntakeRequest request = new ProductTaskFrameIntakeRequest(
                ProductTaskFrameTestFixtures.identity(7L, null, null),
                Optional.empty(),
                ProductTaskFrameTestFixtures.directDecision(),
                ProductTaskFrameTestFixtures.draft(),
                ProductTaskFrameTestFixtures.executionProfile(),
                ProductTaskFrameTestFixtures.CREATED_AT);

        TaskFrameFreezeValidationException failure = assertThrows(
                TaskFrameFreezeValidationException.class,
                () -> adapter.bind(request));

        assertEquals(TaskFrameFreezeValidationCode.ROUTE_NOT_PERSISTENT, failure.code());
        assertEquals(
                "taskFrameFreezeRequest.routingDecision.route",
                failure.path());
    }

    @Test
    void preservesTypedMissingDraftFailure() {
        ProductTaskFrameIntakeRequest request = new ProductTaskFrameIntakeRequest(
                ProductTaskFrameTestFixtures.identity(7L, null, null),
                Optional.empty(),
                ProductTaskFrameTestFixtures.persistentDecision(),
                null,
                ProductTaskFrameTestFixtures.executionProfile(),
                ProductTaskFrameTestFixtures.CREATED_AT);

        TaskFrameFreezeValidationException failure = assertThrows(
                TaskFrameFreezeValidationException.class,
                () -> adapter.bind(request));

        assertEquals(TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING, failure.code());
        assertEquals("taskFrameFreezeRequest.draft", failure.path());
    }

    @Test
    void preservesTypedMissingExecutionProfileFailure() {
        ProductTaskFrameIntakeRequest request = new ProductTaskFrameIntakeRequest(
                ProductTaskFrameTestFixtures.identity(7L, null, null),
                Optional.empty(),
                ProductTaskFrameTestFixtures.persistentDecision(),
                ProductTaskFrameTestFixtures.draft(),
                null,
                ProductTaskFrameTestFixtures.CREATED_AT);

        TaskFrameFreezeValidationException failure = assertThrows(
                TaskFrameFreezeValidationException.class,
                () -> adapter.bind(request));

        assertEquals(TaskFrameFreezeValidationCode.REQUIRED_VALUE_MISSING, failure.code());
        assertEquals("taskFrameFreezeRequest.executionProfile", failure.path());
    }

    @Test
    void preservesCanonicalTaskFrameContractFailure() {
        ProductTaskFrameIntakeRequest request = new ProductTaskFrameIntakeRequest(
                ProductTaskFrameTestFixtures.identity(7L, null, null),
                Optional.empty(),
                ProductTaskFrameTestFixtures.persistentDecision(),
                new TaskFrameDraft(
                        " ",
                        List.of("target"),
                        List.of("deliverable"),
                        List.of()),
                ProductTaskFrameTestFixtures.executionProfile(),
                ProductTaskFrameTestFixtures.CREATED_AT);

        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> adapter.bind(request));

        assertEquals(ViolationCode.REQUIRED_TEXT_BLANK, failure.primaryCode());
        assertEquals("taskFrame.objective", failure.violations().get(0).path());
    }

    private static void assertProductFailure(
            ProductTaskFrameValidationCode code,
            String path,
            Runnable action) {
        ProductTaskFrameValidationException failure = assertThrows(
                ProductTaskFrameValidationException.class,
                action::run);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
    }
}
