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
            groupId = "orynx-group"
    )
    public void consume(
            UserRegisteredEvent event
    ) {

        log.info("Received Event: {}", event.getEmail());
    }
}