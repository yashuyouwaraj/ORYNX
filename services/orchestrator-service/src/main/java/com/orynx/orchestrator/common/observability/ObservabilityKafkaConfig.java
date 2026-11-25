package com.orynx.orchestrator.common.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Automatically wires W3C Trace Context and correlation header propagation onto Spring Kafka
 * producers and listeners without breaking existing KafkaTemplate method signatures or tests.
 */
@Configuration
public class ObservabilityKafkaConfig {

    @Bean
    public BeanPostProcessor kafkaObservabilityBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DefaultKafkaProducerFactory<?, ?> factory) {
                    Map<String, Object> props = factory.getConfigurationProperties();
                    Object existing = props.get("interceptor.classes");
                    String interceptorName = TraceContextProducerInterceptor.class.getName();
                    if (existing == null) {
                        factory.updateConfigs(Map.of("interceptor.classes", List.of(interceptorName)));
                    } else if (existing instanceof List<?> existingList && !existingList.contains(interceptorName)) {
                        java.util.ArrayList<Object> merged = new java.util.ArrayList<>(existingList);
                        merged.add(interceptorName);
                        factory.updateConfigs(Map.of("interceptor.classes", merged));
                    } else if (existing instanceof String existingStr && !existingStr.contains(interceptorName)) {
                        String merged = existingStr.isBlank() ? interceptorName : existingStr + "," + interceptorName;
                        factory.updateConfigs(Map.of("interceptor.classes", merged));
                    }
                } else if (bean instanceof ConcurrentKafkaListenerContainerFactory factory) {
                    factory.setRecordInterceptor(new TraceContextRecordInterceptor<>());
                }
                return bean;
            }
        };
    }

    public static class TraceContextProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {
        @Override
        public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
            if (record != null) {
                KafkaTraceContextPropagator.inject(record.headers(), Context.current());
            }
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
            // No-op: acknowledgement logging handled by producer callbacks
        }

        @Override
        public void close() {
            // No-op
        }

        @Override
        public void configure(Map<String, ?> configs) {
            // No-op
        }
    }

    public static class TraceContextRecordInterceptor<K, V> implements RecordInterceptor<K, V> {
        private static final ThreadLocal<SpanScopeHolder> ACTIVE_SCOPE = new ThreadLocal<>();

        @Override
        public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            if (record == null) {
                return null;
            }
            Tracer tracer = GlobalOpenTelemetry.getTracer("com.orynx.orchestrator.kafka");
            Span span = KafkaTraceContextPropagator.startConsumerSpan(
                    tracer,
                    "orynx.kafka.consume." + record.topic(),
                    record.headers(),
                    record.topic()
            );
            Scope scope = span.makeCurrent();
            KafkaTraceContextPropagator.populateMdcFromHeaders(record.headers(), Context.current());
            ACTIVE_SCOPE.set(new SpanScopeHolder(span, scope));
            return record;
        }

        @Override
        public void failure(ConsumerRecord<K, V> record, Exception exception, Consumer<K, V> consumer) {
            SpanScopeHolder holder = ACTIVE_SCOPE.get();
            if (holder != null && holder.span != null && exception != null) {
                holder.span.setAttribute("error.type", exception.getClass().getSimpleName());
                holder.span.setAttribute("error.message", ObservabilitySanitizer.safeErrorMessage(exception));
            }
        }

        @Override
        public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            cleanup();
        }

        @Override
        public void clearThreadState(Consumer<?, ?> consumer) {
            cleanup();
        }

        private void cleanup() {
            SpanScopeHolder holder = ACTIVE_SCOPE.get();
            if (holder != null) {
                ACTIVE_SCOPE.remove();
                try {
                    if (holder.scope != null) {
                        holder.scope.close();
                    }
                } finally {
                    if (holder.span != null) {
                        holder.span.end();
                    }
                }
            }
            KafkaTraceContextPropagator.clearKafkaMdc();
        }

        private record SpanScopeHolder(Span span, Scope scope) {}
    }
}
