package com.orynx.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "jwt.secret=testSecretKeyForJwtSigning1234567890!@#$"
})
class GatewayRouteConfigurationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    @DisplayName("Should load all configured routes in RouteLocator")
    void shouldLoadAllConfiguredRoutes() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();

        List<String> routeIds = routes.stream().map(Route::getId).toList();
        assertThat(routeIds).contains(
                "auth-service",
                "user-service",
                "orchestrator-workflows",
                "orchestrator-ai",
                "orchestrator-platform",
                "orchestrator-analytics",
                "orchestrator-performance",
                "orchestrator-timeline",
                "orchestrator-websocket",
                "planning-service"
        );
    }
}
