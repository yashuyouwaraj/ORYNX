package com.orynx.execution.worker;

public class WorkerQueueFullException extends RuntimeException {
    public WorkerQueueFullException(String message) {
        super(message);
    }

    public WorkerQueueFullException(String message, Throwable cause) {
        super(message, cause);
    }
}
