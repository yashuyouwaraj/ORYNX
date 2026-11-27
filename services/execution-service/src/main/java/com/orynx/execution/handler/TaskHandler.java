package com.orynx.execution.handler;

public interface TaskHandler {

    String getTaskType();

    TaskHandlerResult execute(TaskHandlerContext context) throws Exception;
}
