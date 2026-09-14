package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReactPlanTaskSkillPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReactPlanTaskCheckpointRepository checkpoints = mock(ReactPlanTaskCheckpointRepository.class);
    private final ReactPlanTaskSkillPolicy policy = new ReactPlanTaskSkillPolicy(checkpoints, json);

    @Test void enforcesTheFrozenSnapshotAndDoesNotLookUpMutableInstallation() throws Exception {
        var authority = Map.of("skill", Map.of("allowedTools", java.util.List.of("search_past_conversations")));
        String digest = store(authority);
        assertThat(policy.allowedTools("task", 7, digest)).containsExactly("search_past_conversations");
    }

    @Test void emptySkillDeniesAllWhileAbsentSkillInheritsTaskPermissions() throws Exception {
        String empty = store(Map.of("skill", Map.of("allowedTools", java.util.List.of())));
        assertThat(policy.allowedTools("task", 7, empty)).isEmpty();
        String absent = store(Map.of("instruction", "read"));
        assertThat(policy.allowedTools("task", 7, absent)).isNull();
    }

    @Test void missingCrossOwnerAndChangedAuthorityFailClosed() throws Exception {
        assertThatThrownBy(() -> policy.allowedTools("missing", 7, "wrong")).isInstanceOf(IllegalStateException.class);
        String digest = store(Map.of("instruction", "read"));
        assertThatThrownBy(() -> policy.allowedTools("task", 8, digest)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.allowedTools("task", 7, "wrong")).isInstanceOf(IllegalStateException.class);
        when(checkpoints.findById("task")).thenReturn(Optional.of(new ReactPlanTaskCheckpointEntity(
                "task", digest, 7, 2, 3, "running", 0, "{\"authority\":{\"instruction\":\"changed\"}}", LocalDateTime.now())));
        assertThatThrownBy(() -> policy.allowedTools("task", 7, digest)).isInstanceOf(IllegalStateException.class);
    }

    private String store(Map<String, ?> authority) throws Exception {
        String digest = ReactPlanCanonicalJson.digest(json, authority);
        when(checkpoints.findById("task")).thenReturn(Optional.of(new ReactPlanTaskCheckpointEntity(
                "task", digest, 7, 2, 3, "running", 0,
                json.writeValueAsString(Map.of("authority", authority)), LocalDateTime.now())));
        return digest;
    }
}
