package com.orynx.execution.handler;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;
import java.util.function.BooleanSupplier;

@Getter
@Builder
public class TaskHandlerContext {

    private final Long workflowId;
    private final String executionId;
    private final Long generation;
    private final String taskKey;
    private final String taskName;
    private final String taskType;
    private final int attempt;
    private final long timeoutMs;

    @Builder.Default
    private final Map<String, Object> config = Collections.emptyMap();

    @Builder.Default
    private final Map<String, Map<String, Object>> upstreamOutputs = Collections.emptyMap();

    @Builder.Default
    private final BooleanSupplier cancellationCheck = () -> false;

    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted() || (cancellationCheck != null && cancellationCheck.getAsBoolean());
    }
}
