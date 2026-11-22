package com.orynx.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Enforces immutable Actuator endpoint exposure and health detail hiding after Spring Boot
 * property binding, preventing external environment variable overrides from re-exposing
 * diagnostic endpoints or sensitive health details.
 */
@Component
@Slf4j
public class ActuatorSecurityEnforcer implements BeanPostProcessor, PriorityOrdered {

    private static final Set<String> ALLOWED_ENDPOINTS = Set.of("health");
    private static final Set<String> EXCLUDED_ENDPOINTS = Set.of(
            "env", "configprops", "beans", "mappings", "heapdump",
            "threaddump", "loggers", "shutdown", "conditions", "scheduledtasks", "info", "gateway"
    );

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof WebEndpointProperties props) {
            props.getExposure().setInclude(new LinkedHashSet<>(ALLOWED_ENDPOINTS));
            props.getExposure().setExclude(new LinkedHashSet<>(EXCLUDED_ENDPOINTS));
            props.getDiscovery().setEnabled(false);
            log.info("Enforced immutable Actuator exposure policy on {}: include={}", beanName, ALLOWED_ENDPOINTS);
        } else if (bean instanceof HealthEndpointProperties healthProps) {
            healthProps.setShowDetails(Show.NEVER);
            healthProps.setShowComponents(Show.NEVER);
            if (healthProps.getGroup() != null) {
                healthProps.getGroup().values().forEach(group -> {
                    group.setShowDetails(Show.NEVER);
                    group.setShowComponents(Show.NEVER);
                });
            }
            log.info("Enforced immutable health detail suppression on {}: showDetails=NEVER", beanName);
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
