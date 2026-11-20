package com.orynx.execution.coordination;

public class StaleWorkerExecutionException extends RuntimeException {
    public StaleWorkerExecutionException(String message) {
        super(message);
    }
}
