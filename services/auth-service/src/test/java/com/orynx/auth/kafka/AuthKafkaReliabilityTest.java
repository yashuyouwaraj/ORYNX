package com.orynx.auth.kafka;

import com.orynx.auth.common.config.KafkaReliabilityConfig;
import com.orynx.auth.user.event.UserRegisteredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthKafkaReliabilityTest {

    @Mock
    private KafkaTemplate<String, UserRegisteredEvent> kafkaTemplate;

    @Test
    @DisplayName("auth-service KafkaProducer keys UserRegisteredEvent by userId and falls back cleanly")
    void authProducerKeysByUserId() {
        KafkaProducer producer = new KafkaProducer(kafkaTemplate);
        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .userId(42L)
                .email("user@orynx.io")
                .build();

        producer.publishUserRegisteredEvent(event);
        verify(kafkaTemplate).send("user-registered", "42", event);

        UserRegisteredEvent emailFallback = UserRegisteredEvent.builder()
                .userId(null)
                .email("fallback@orynx.io")
                .build();
        producer.publishUserRegisteredEvent(emailFallback);
        verify(kafkaTemplate).send("user-registered", "fallback@orynx.io", emailFallback);

        producer.publishUserRegisteredEvent(null);
    }

    @Test
    @DisplayName("auth-service KafkaConsumer aligns groupId and safely ignores null events")
    void authConsumerAlignsGroupIdAndHandlesNull() throws NoSuchMethodException {
        Method method = KafkaConsumer.class.getMethod("consume", UserRegisteredEvent.class);
        KafkaListener listener = method.getAnnotation(KafkaListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.topics()).containsExactly("user-registered");
        assertThat(listener.groupId()).isEqualTo("orynx-auth-group");

        KafkaConsumer consumer = new KafkaConsumer();
        consumer.consume(null);
        consumer.consume(UserRegisteredEvent.builder().userId(1L).email("test@orynx.io").build());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("auth-service configures ErrorHandlingDeserializer, producer idempotence, and DefaultErrorHandler")
    void authKafkaPropertiesAndErrorHandlerConfigured() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertThat(in).isNotNull();
            props.load(in);
        }

        assertThat(props.getProperty("spring.kafka.consumer.group-id")).isEqualTo("orynx-auth-group");
        assertThat(props.getProperty("spring.kafka.consumer.value-deserializer"))
                .isEqualTo("org.springframework.kafka.support.serializer.ErrorHandlingDeserializer");
        assertThat(props.getProperty("spring.kafka.consumer.properties.spring.deserializer.value.delegate.class"))
                .isEqualTo("org.springframework.kafka.support.serializer.JsonDeserializer");
        assertThat(props.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
        assertThat(props.getProperty("spring.kafka.producer.retries")).isEqualTo("3");
        assertThat(props.getProperty("spring.kafka.producer.properties.enable.idempotence")).isEqualTo("true");

        KafkaReliabilityConfig config = new KafkaReliabilityConfig();
        CommonErrorHandler handler = config.kafkaCommonErrorHandler(500L, 3L);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
    }
}
