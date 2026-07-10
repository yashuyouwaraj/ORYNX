package com.orynx.orchestrator.platform;

import com.orynx.orchestrator.platform.dto.PlatformHealthResponse;
import org.springframework.stereotype.Service;

@Service
public class PlatformHealthService {

    public PlatformHealthResponse health() {

        return PlatformHealthResponse.builder()
                .gateway("UP")
                .orchestrator("UP")
                .planning("UP")
                .build();

    }

}