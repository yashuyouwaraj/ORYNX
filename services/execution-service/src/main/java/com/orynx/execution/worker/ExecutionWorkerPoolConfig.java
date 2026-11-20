package com.orynx.execution.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(ExecutionWorkerProperties.class)
@RequiredArgsConstructor
@Slf4j
public class ExecutionWorkerPoolConfig {

    private final ExecutionWorkerProperties properties;

    @Bean(name = "workflowExecutionExecutor")
    public ThreadPoolTaskExecutor workflowExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix("workflow-worker-");

        // Controlled rejection: abort with an explicit exception rather than caller-runs or silent drop
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // Graceful shutdown configuration
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());

        executor.initialize();

        log.info(
                "Initialized workflow execution worker pool [core={}, max={}, queue={}, awaitTermination={}s]",
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getQueueCapacity(),
                properties.getAwaitTerminationSeconds()
        );

        return executor;
    }
}
