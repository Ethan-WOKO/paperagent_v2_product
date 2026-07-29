package com.yanban.api.agent.v2.compatibility;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(V2ProductAvailabilityProperties.class)
public class V2ProductAvailabilityConfiguration {

    @Bean
    V2ProductAvailability v2ProductAvailability(
            V2ProductAvailabilityProperties properties) {
        return new V2ProductAvailability(properties.isEnabled());
    }
}
