package com.orynx.orchestrator.platform;

import com.orynx.orchestrator.platform.dto.PlatformHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformHealthController {
    private final PlatformHealthService service;

    @GetMapping("/health")
    public PlatformHealthResponse health(){
        return service.health();
    }
}
