package com.yanban.api.agent.reactplan;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/react-agent/observability")
@ConditionalOnBean(ReactPlanObservabilityService.class)
final class ReactPlanObservabilityAdminController {
    private final ReactPlanObservabilityService observability;

    ReactPlanObservabilityAdminController(ReactPlanObservabilityService observability) {
        this.observability = observability;
    }

    @GetMapping
    Map<String, Object> summary() {
        return observability.adminSummary();
    }
}
