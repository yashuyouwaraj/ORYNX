package com.orynx.execution.execution.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaskExecutionResult {
    private String taskName;
    private boolean success;
    private int attempts;
}
