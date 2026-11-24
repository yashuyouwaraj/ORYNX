package com.orynx.orchestrator.coordination.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisCoordinationConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> admitWorkflowScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/admit_workflow.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> releaseWorkflowScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/release_workflow.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public RedisScript<Long> releaseLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/release_lock.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
