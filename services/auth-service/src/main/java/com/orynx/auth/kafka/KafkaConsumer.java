package com.orynx.auth.kafka;

import com.orynx.auth.user.event.UserRegisteredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaConsumer {

    @KafkaListener(
            topics = "user-registered",
            groupId = "orynx-auth-group"
    )
    public void consume(
            UserRegisteredEvent event
    ) {
        if (event == null) {
            log.warn("Received null UserRegisteredEvent. Ignoring.");
            return;
        }
        log.info("Received Event: {}", event.getEmail());
    }
}