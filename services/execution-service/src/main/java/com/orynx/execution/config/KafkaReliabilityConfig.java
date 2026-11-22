package com.orynx.execution.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configures bounded consumer retries and poison-message recovery for execution-service
 * Kafka listeners, forwarding exhausted/poison records to a dead-letter topic (.DLT)
 * while preserving manual acknowledgment semantics on transient capacity backpressure
 * within retry bounds.
 */
@Configuration
@Slf4j
public class KafkaReliabilityConfig {

    private static final String DLT_SUFFIX = ".DLT";

    private final ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider;

    public KafkaReliabilityConfig() {
        this.kafkaTemplateProvider = null;
    }

    @Autowired
    public KafkaReliabilityConfig(ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider) {
        this.kafkaTemplateProvider = kafkaTemplateProvider;
    }

    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(
            @Value("${orynx.kafka.consumer.retry.interval-ms:500}") long retryIntervalMs,
            @Value("${orynx.kafka.consumer.retry.max-attempts:3}") long maxRetries
    ) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            String topic = record != null ? record.topic() : "unknown";
            int partition = record != null ? record.partition() : -1;
            long offset = record != null ? record.offset() : -1L;
            Object key = record != null ? record.key() : null;
            String exceptionType = exception != null ? exception.getClass().getName() : "unknown";
            String cause = exception != null ? exception.getMessage() : "unknown";

            log.error(
                    "Kafka consumer recovery exhausted/skipped poison record: topic={}, partition={}, offset={}, key={}, exceptionType={}, cause={}",
                    topic, partition, offset, key, exceptionType, cause, exception
            );
            publishToDlt(record, exception, topic, partition, offset, key, exceptionType, cause);
        };

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, maxRetries)
        );
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                ConversionException.class
        );
        errorHandler.setCommitRecovered(true);

        log.info("Configured Kafka DefaultErrorHandler with FixedBackOff(intervalMs={}, maxRetries={})",
                retryIntervalMs, maxRetries);
        return errorHandler;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void publishToDlt(
            ConsumerRecord<?, ?> record,
            Exception exception,
            String topic,
            int partition,
            long offset,
            Object key,
            String exceptionType,
            String cause
    ) {
        if (kafkaTemplateProvider == null || record == null || record.topic() == null
                || record.topic().endsWith(DLT_SUFFIX)) {
            return;
        }
        KafkaTemplate template = kafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            Map<String, Object> dltRecord = new LinkedHashMap<>();
            dltRecord.put("originalTopic", topic);
            dltRecord.put("partition", partition);
            dltRecord.put("offset", offset);
            dltRecord.put("key", key != null ? String.valueOf(key) : null);
            dltRecord.put("timestamp", record.timestamp());
            dltRecord.put("exceptionType", exceptionType);
            dltRecord.put("exceptionMessage", cause);
            dltRecord.put("payload", record.value());
            String rawPayload = extractRawDeserializationPayload(exception);
            if (rawPayload != null) {
                dltRecord.put("rawPayload", rawPayload);
            }

            String dltTopic = record.topic() + DLT_SUFFIX;
            String dltKey = key != null ? String.valueOf(key) : null;
            template.send(dltTopic, dltKey, dltRecord);
            log.warn("Forwarded unrecoverable Kafka record to dead-letter topic={} (key={}, offset={})",
                    dltTopic, dltKey, offset);
        } catch (Exception dltEx) {
            log.error("Failed to publish record to DLT for topic={}, offset={}: {}",
                    topic, offset, dltEx.getMessage(), dltEx);
        }
    }

    private String extractRawDeserializationPayload(Throwable ex) {
        Throwable curr = ex;
        for (int depth = 0; curr != null && depth < 10; depth++, curr = curr.getCause()) {
            if (curr instanceof DeserializationException de && de.getData() != null) {
                return new String(de.getData(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
