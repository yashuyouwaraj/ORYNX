package com.orynx.orchestrator.platform.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlatformHealthResponse {

    private String gateway;

    private String orchestrator;

    private String planning;

}
