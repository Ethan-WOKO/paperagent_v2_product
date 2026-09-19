package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class ReactPlanEngineSelectionTest {
    @Test
    void defaultsToTsAndPermanentlyRejectsPython() {
        var selection = new ReactPlanEngineSelection(mock(ReactPlanTurnIntakeRepository.class));
        assertThat(selection.engine("old")).isEqualTo("TS");
        selection.requireEnabled(null);
        selection.requireEnabled("TS");
        assertThatThrownBy(() -> selection.requireEnabled("PYTHON"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.GONE))
                .hasMessageContaining("PYTHON_ENGINE_RETIRED");
    }

    @Test
    void historicalPythonOperationsNeverReachTsOrAnyHttpService() {
        var json = new ObjectMapper();
        var repository = mock(ReactPlanTurnIntakeRepository.class);
        String id = "task." + "a".repeat(64);
        var intake = new ReactPlanTurnIntakeEntity(1, 2, "request.1234567890123456", "a".repeat(64), 3, 4, id, LocalDateTime.now());
        intake.selectEngine("PYTHON");
        when(repository.findByTaskId(id)).thenReturn(Optional.of(intake));
        var http = mock(HttpClient.class);
        var client = new ReactPlanEngineClient(json, new ReactPlanRuntimeProperties(), http);
        ReflectionTestUtils.setField(client, "selection", new ReactPlanEngineSelection(repository));
        assertThatThrownBy(() -> client.submit(json.createObjectNode().put("taskId", id))).hasMessageContaining("PYTHON_ENGINE_RETIRED");
        assertThatThrownBy(() -> client.task(id)).hasMessageContaining("PYTHON_ENGINE_RETIRED");
        assertThatThrownBy(() -> client.cancel(id, "cancel.1234567890123456")).hasMessageContaining("PYTHON_ENGINE_RETIRED");
        assertThatThrownBy(() -> client.answer(id, json.createObjectNode())).hasMessageContaining("PYTHON_ENGINE_RETIRED");
        assertThatThrownBy(() -> client.events(id, 0)).hasMessageContaining("PYTHON_ENGINE_RETIRED");
        verifyNoInteractions(http);
    }
}
