package com.yanban.api.agent.reactplan;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Task routing is an intake fact, never a mutable global engine switch. */
@Service
public class ReactPlanEngineSelection {
    private final ReactPlanTurnIntakeRepository intakes;
    private final ReactPlanRuntimeProperties properties;

    ReactPlanEngineSelection(ReactPlanTurnIntakeRepository intakes, ReactPlanRuntimeProperties properties) {
        this.intakes = intakes;
        this.properties = properties;
    }

    public static String normalize(String engine) {
        if (engine == null || engine.isBlank()) return "TS";
        if (!engine.equals("TS") && !engine.equals("PYTHON")) throw new IllegalArgumentException("Unknown engine");
        return engine;
    }

    public String engine(String taskId) {
        return intakes.findByTaskId(taskId).map(ReactPlanTurnIntakeEntity::engine).orElse("TS");
    }

    public boolean readOnly(String taskId) { return "PYTHON".equals(engine(taskId)); }

    void requireEnabled(String engine) {
        if ("PYTHON".equals(normalize(engine)) && !properties.isPythonEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Python analysis engine is disabled");
        }
    }
}
