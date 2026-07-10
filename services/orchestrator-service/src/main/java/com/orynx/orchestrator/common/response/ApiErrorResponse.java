package com.orynx.orchestrator.common.response;


import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiErrorResponse {
    private String timestamp;

    private int status;

    private String error;

    private String path;

    private String requestId;
}
