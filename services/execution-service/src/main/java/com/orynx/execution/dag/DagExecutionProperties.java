package com.orynx.execution.dag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orynx.execution.dag")
@Getter
@Setter
public class DagExecutionProperties {

    private int corePoolSize = 8;

    private int maxPoolSize = 16;

    private int queueCapacity = 100;

    private int maxPerWorkflowConcurrency = 4;

    private int awaitTerminationSeconds = 30;

    private int maxTaskConfigBytes = 16_384;

    private int maxTaskOutputBytes = 32_768;

    private int maxPayloadNestingDepth = 8;

    private int maxTransformParts = 256;
}
