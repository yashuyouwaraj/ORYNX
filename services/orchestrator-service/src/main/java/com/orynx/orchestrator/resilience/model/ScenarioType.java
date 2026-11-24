package com.orynx.orchestrator.resilience.model;

/**
 * Supported failure injection scenario types in OrynX.
 */
public enum ScenarioType {
    REDIS_INTERRUPTION("redis", "Simulates Redis unavailability, latency, or failure policy transitions"),
    KAFKA_INTERRUPTION("kafka", "Simulates Kafka broker unavailability, event publication failures, or consumer lag"),
    POSTGRES_INTERRUPTION("postgres", "Simulates PostgreSQL connection drops, transaction timeouts, or rollback scenarios"),
    WORKER_INTERRUPTION("worker", "Simulates execution worker crashes, heartbeat failure, lease expiration, and fencing"),
    ORCHESTRATOR_INTERRUPTION("orchestrator", "Simulates orchestrator restart, leader lock loss, and startup reconciliation"),
    NETWORK_LATENCY("network", "Introduces controlled artificial latency and connection degradation between services");

    private final String defaultComponent;
    private final String description;

    ScenarioType(String defaultComponent, String description) {
        this.defaultComponent = defaultComponent;
        this.description = description;
    }

    public String getDefaultComponent() {
        return defaultComponent;
    }

    public String getDescription() {
        return description;
    }
}
