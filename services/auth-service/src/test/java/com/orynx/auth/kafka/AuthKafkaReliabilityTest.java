package com.orynx.auth.kafka;

import com.orynx.auth.common.config.KafkaReliabilityConfig;
import com.orynx.auth.user.event.UserRegisteredEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthKafkaReliabilityTest {

    @Mock
    private KafkaTemplate<String, UserRegisteredEvent> kafkaTemplate;

    @Test
    @DisplayName("auth-service KafkaProducer keys UserRegisteredEvent by userId and skips unkeyed events")
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
        producer.publishUserRegisteredEvent(UserRegisteredEvent.builder().userId(null).email(null).build());
        producer.publishUserRegisteredEvent(UserRegisteredEvent.builder().userId(null).email("   ").build());
        verifyNoMoreInteractions(kafkaTemplate);
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
    @DisplayName("auth-service configures ErrorHandlingDeserializer, producer idempotence, DefaultErrorHandler, and DLT forwarding")
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

        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<?, ?>> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<Object, Object> dltKafkaTemplate = mock(KafkaTemplate.class);
        doReturn(dltKafkaTemplate).when(provider).getIfAvailable();

        KafkaReliabilityConfig config = new KafkaReliabilityConfig(provider);
        CommonErrorHandler handler = config.kafkaCommonErrorHandler(0L, 1L);
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);

        Consumer<?, ?> consumerMock = mock(Consumer.class);
        MessageListenerContainer containerMock = mock(MessageListenerContainer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("user-registered", 0, 7L, "42", null);

        // IllegalArgumentException must be retried first, NOT skipped immediately to DLT
        try {
            handler.handleOne(new IllegalArgumentException("transient error"), record, consumerMock, containerMock);
        } catch (Exception ignored) {
        }
        verifyNoInteractions(dltKafkaTemplate);

        // DeserializationException skips retries and forwards immediately to user-registered.DLT
        DeserializationException deserEx = new DeserializationException(
                "bad json", "{corrupt".getBytes(StandardCharsets.UTF_8), false, new RuntimeException("parse"));
        handler.handleOne(new RuntimeException(deserEx), record, consumerMock, containerMock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dltCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dltKafkaTemplate).send(eq("user-registered.DLT"), eq("42"), dltCaptor.capture());
        assertThat(dltCaptor.getValue())
                .containsEntry("originalTopic", "user-registered")
                .containsEntry("key", "42")
                .containsEntry("rawPayload", "{corrupt");
    }
}
