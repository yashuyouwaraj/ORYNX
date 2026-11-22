package com.orynx.auth.kafka;

import com.orynx.auth.user.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    public static final String TOPIC = "user-registered";

    private final KafkaTemplate<String, UserRegisteredEvent> kafkaTemplate;

    public void publishUserRegisteredEvent(UserRegisteredEvent event) {
        if (event == null || (event.getUserId() == null && (event.getEmail() == null || event.getEmail().isBlank()))) {
            log.warn("Attempted to publish null or unkeyed UserRegisteredEvent. Skipping.");
            return;
        }
        String key = event.getUserId() != null ? String.valueOf(event.getUserId()) : event.getEmail();
        CompletableFuture<SendResult<String, UserRegisteredEvent>> future =
                kafkaTemplate.send(TOPIC, key, event);
        if (future != null) {
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to deliver UserRegisteredEvent for userId={} (key={}): {}",
                            event.getUserId(), key, ex.getMessage(), ex);
                }
            });
        }
    }
}