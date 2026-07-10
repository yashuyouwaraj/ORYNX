package com.orynx.orchestrator.integration.planning.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "planning.service")
public class PlanningServiceProperties {
    /**
     * Base URL of Planning Service
     * Example:
     * http://localhost:8083
     */
    private String url;

    /**
     * Connection timeout (milliseconds)
     */
    private int connectTimeout = 5000;

    /**
     * Read timeout (milliseconds)
     */
    private int readTimeout = 10000;

}
