package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ReactPlanEngineSelectionTest {
    @Test
    void legacyDefaultsTsAndPythonIsOptIn() {
        var repository = mock(ReactPlanTurnIntakeRepository.class);
        var properties = new ReactPlanRuntimeProperties();
        var selection = new ReactPlanEngineSelection(repository, properties);
        assertThat(selection.engine("old")).isEqualTo("TS");
        assertThatThrownBy(() -> selection.requireEnabled("PYTHON")).hasMessageContaining("disabled");
        properties.setPythonEnabled(true);
        properties.setPythonServiceToken("p".repeat(32));
        assertThat(properties.isPythonConfigurationSafe()).isTrue();
        assertThatThrownBy(() -> ReactPlanEngineSelection.normalize("other")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesAllOperationsUsingPersistedIntakeNotNewSelection() throws Exception {
        var json = new ObjectMapper();
        var repository = mock(ReactPlanTurnIntakeRepository.class);
        var properties = new ReactPlanRuntimeProperties();
        properties.setPythonEnabled(true);
        properties.setPythonServiceToken("p".repeat(32));
        properties.setEngineServiceToken("t".repeat(32));
        String id = "task." + "a".repeat(64);
        var intake = new ReactPlanTurnIntakeEntity(1, 2, "request.1234567890123456", "a".repeat(64), 3, 4, id, LocalDateTime.now());
        intake.selectEngine("PYTHON");
        when(repository.findByTaskId(id)).thenReturn(Optional.of(intake));
        var http = mock(HttpClient.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new ReactPlanEngineClient(json, properties, http);
        ReflectionTestUtils.setField(client, "selection", new ReactPlanEngineSelection(repository, properties));
        client.submit(json.createObjectNode().put("taskId", id));
        client.task(id);
        client.cancel(id, "cancel.1234567890123456");
        client.answer(id, json.createObjectNode());
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(4)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.uri().getPort()).isEqualTo(8097);
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + "p".repeat(32));
        });
        properties.setPythonEnabled(false);
        assertThatThrownBy(() -> client.task(id)).hasMessageContaining("disabled");
        verify(http, times(4)).send(any(), any(HttpResponse.BodyHandler.class));
    }
}
