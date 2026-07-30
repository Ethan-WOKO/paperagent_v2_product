package com.yanban.api.agent.v2.compatibility;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class V2ProductAvailability {

    public static final String LITERATURE_SEARCH = "literature.search";
    public static final String PROJECT_READ_ANALYSIS = "project.read-analysis";
    public static final String PROJECT_CANDIDATE = "project.candidate";
    public static final String NATURAL_LANGUAGE_TURN = "agent.turn";

    private static final int FORMAT_VERSION = 1;
    private static final String UNAVAILABLE_MESSAGE =
            "V2 Agent capabilities are unavailable";
    private static final List<String> CAPABILITIES = List.of(
            LITERATURE_SEARCH,
            PROJECT_READ_ANALYSIS,
            PROJECT_CANDIDATE,
            NATURAL_LANGUAGE_TURN);

    private final boolean enabled;

    public V2ProductAvailability(boolean enabled) {
        this.enabled = enabled;
    }

    public static V2ProductAvailability enabledByDefault() {
        return new V2ProductAvailability(true);
    }

    public V2ProductAvailabilityDocument document() {
        return new V2ProductAvailabilityDocument(
                FORMAT_VERSION, enabled, CAPABILITIES);
    }

    public void requireAvailable(String capability) {
        if (!CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("Unsupported V2 capability");
        }
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }
}
