package com.orynx.orchestrator.resilience.model;

/**
 * Categorization of resilience fault injection capability.
 * Enforces honesty in experiment reporting and verification depth.
 */
public enum InjectionLevel {
    APPLICATION_SCOPED("Fault injected into genuine application code paths via scoped contextual interception"),
    ISOLATED_INFRASTRUCTURE("Fault injected into dedicated, verified isolated infrastructure containers"),
    SIMULATION("In-memory or mock-based logical simulation for unit verification");

    private final String description;

    InjectionLevel(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
