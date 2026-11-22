package com.orynx.orchestrator.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configures bounded consumer retries and poison-message recovery so transient
 * processing errors retry cleanly while malformed or non-retryable records do
 * not trap partitions in infinite retry loops.
 */
@Configuration
@Slf4j
public class KafkaReliabilityConfig {

    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(
            @Value("${orynx.kafka.consumer.retry.interval-ms:500}") long retryIntervalMs,
            @Value("${orynx.kafka.consumer.retry.max-attempts:3}") long maxRetries
    ) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> log.error(
                "Kafka consumer recovery exhausted/skipped poison record: topic={}, partition={}, offset={}, key={}, cause={}",
                record != null ? record.topic() : "unknown",
                record != null ? record.partition() : -1,
                record != null ? record.offset() : -1L,
                record != null ? record.key() : null,
                exception != null ? exception.getMessage() : "unknown",
                exception
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, maxRetries)
        );
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                ConversionException.class,
                IllegalArgumentException.class
        );
        errorHandler.setCommitRecovered(true);

        log.info("Configured Kafka DefaultErrorHandler with FixedBackOff(intervalMs={}, maxRetries={})",
                retryIntervalMs, maxRetries);
        return errorHandler;
    }
}
