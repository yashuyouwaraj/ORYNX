package com.orynx.execution.handler;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

@Getter
@Builder
public class TaskHandlerResult {

    private final boolean success;

    @Builder.Default
    private final Map<String, Object> output = Collections.emptyMap();

    private final String errorMessage;

    public static TaskHandlerResult ok(Map<String, Object> output) {
        return TaskHandlerResult.builder()
                .success(true)
                .output(output != null ? output : Collections.emptyMap())
                .build();
    }

    public static TaskHandlerResult failed(String errorMessage) {
        return TaskHandlerResult.builder()
                .success(false)
                .errorMessage(errorMessage != null ? errorMessage : "Task execution failed")
                .build();
    }
}
