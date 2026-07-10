package com.orynx.orchestrator.integration.planning.exception;

public class PlanningServiceException extends RuntimeException {
    public PlanningServiceException(String message){
        super(message);
    }

    public PlanningServiceException(String message, Throwable cause){
        super(message,cause);
    }
}
