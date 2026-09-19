package com.yanban.api.agent.reactplan;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Task routing is an intake fact, never a mutable global engine switch. */
@Service
public class ReactPlanEngineSelection {
    private final ReactPlanTurnIntakeRepository intakes;

    ReactPlanEngineSelection(ReactPlanTurnIntakeRepository intakes) {
        this.intakes = intakes;
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
        if ("PYTHON".equals(normalize(engine))) {
            throw new ResponseStatusException(HttpStatus.GONE, "PYTHON_ENGINE_RETIRED: Python demo has been removed; create a new TS task");
        }
    }
}
