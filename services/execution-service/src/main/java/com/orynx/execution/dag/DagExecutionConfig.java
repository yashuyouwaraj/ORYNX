package com.orynx.execution.dag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(DagExecutionProperties.class)
@RequiredArgsConstructor
@Slf4j
public class DagExecutionConfig {

    private final DagExecutionProperties properties;

    @Bean(name = "dagTaskExecutor")
    public ThreadPoolTaskExecutor dagTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, properties.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(properties.getCorePoolSize(), properties.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(10, properties.getQueueCapacity()));
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("dag-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.initialize();

        log.info("Initialized bounded DAG task executor [core={}, max={}, queue={}, perWorkflowLimit={}]",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                properties.getQueueCapacity(),
                properties.getMaxPerWorkflowConcurrency());
        return executor;
    }
}
