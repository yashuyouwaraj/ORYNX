package com.orynx.orchestrator.common.config;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Configures bounded consumer retries and poison-message recovery so transient
 * processing errors retry cleanly while malformed or non-retryable records are
 * durably forwarded to a dead-letter topic (.DLT) with broker confirmation
 * before the original consumer offset is committed.
 */
@Configuration
@Slf4j
public class KafkaReliabilityConfig {

    private static final String DLT_SUFFIX = ".DLT";
    private static final long DEFAULT_DLT_PUBLISH_TIMEOUT_MS = 10_000L;

    private final ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider;

    @Value("${orynx.kafka.consumer.dlt.timeout-ms:10000}")
    private long dltPublishTimeoutMs = DEFAULT_DLT_PUBLISH_TIMEOUT_MS;

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
        if (record == null || record.topic() == null || record.topic().endsWith(DLT_SUFFIX)) {
            throw new IllegalStateException("Cannot recover invalid or recursive DLT record for topic=" + topic);
        }
        if (kafkaTemplateProvider == null) {
            throw new IllegalStateException("KafkaTemplate unavailable for DLT recovery on topic=" + topic);
        }
        KafkaTemplate template = kafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("KafkaTemplate unavailable for DLT recovery on topic=" + topic);
        }

        String dltTopic = record.topic() + DLT_SUFFIX;
        String dltKey = key != null ? String.valueOf(key) : null;
        long timeoutMs = dltPublishTimeoutMs > 0 ? dltPublishTimeoutMs : DEFAULT_DLT_PUBLISH_TIMEOUT_MS;
        try {
            Map<String, Object> dltRecord = new LinkedHashMap<>();
            dltRecord.put("originalTopic", topic);
            dltRecord.put("partition", partition);
            dltRecord.put("offset", offset);
            dltRecord.put("key", dltKey);
            dltRecord.put("timestamp", record.timestamp());
            dltRecord.put("exceptionType", exceptionType);
            dltRecord.put("exceptionMessage", cause);
            dltRecord.put("payload", record.value());
            String rawPayload = extractRawDeserializationPayload(exception);
            if (rawPayload != null) {
                dltRecord.put("rawPayload", rawPayload);
            }

            CompletableFuture<?> future = template.send(dltTopic, dltKey, dltRecord);
            if (future == null) {
                throw new IllegalStateException("KafkaTemplate.send returned null future for DLT topic=" + dltTopic);
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.warn("Forwarded unrecoverable Kafka record to dead-letter topic={} (key={}, offset={})",
                    dltTopic, dltKey, offset);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while awaiting DLT broker confirmation for topic={}, offset={}: {}",
                    topic, offset, ie.getMessage(), ie);
            throw new IllegalStateException(
                    "Interrupted while awaiting DLT broker confirmation for topic=" + topic + ", offset=" + offset, ie);
        } catch (Exception dltEx) {
            log.error("Failed to confirm DLT delivery to {} for topic={}, offset={}: {}",
                    dltTopic, topic, offset, dltEx.getMessage(), dltEx);
            throw new IllegalStateException(
                    "Failed to confirm DLT delivery to " + dltTopic + " for topic=" + topic + ", offset=" + offset, dltEx);
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
