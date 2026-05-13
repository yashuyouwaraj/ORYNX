package com.orynx.auth.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    @GetMapping("/api/v1/auth/health")
    public String health() {
        return "Auth Service Running";
    }
}