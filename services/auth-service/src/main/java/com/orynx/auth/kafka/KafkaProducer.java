package com.orynx.auth.kafka;

import com.orynx.auth.user.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaProducer {

    private final KafkaTemplate<String, UserRegisteredEvent> kafkaTemplate;

    public void publishUserRegisteredEvent(
            UserRegisteredEvent event
    ) {

        kafkaTemplate.send(
                "user-registered",
                event
        );
    }
}