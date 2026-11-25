package com.orynx.execution.observability;

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
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

@Configuration
public class ExecutionObservabilityKafkaConfig {

    @Bean
    public static BeanPostProcessor executionKafkaObservabilityBeanPostProcessor() {
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
                    RecordInterceptor existing = getExistingRecordInterceptor(factory);
                    if (existing == null) {
                        factory.setRecordInterceptor(new TraceContextRecordInterceptor<>());
                    } else if (!(existing instanceof TraceContextRecordInterceptor)
                            && !(existing instanceof ComposingRecordInterceptor)) {
                        factory.setRecordInterceptor(new ComposingRecordInterceptor<>(
                                new TraceContextRecordInterceptor<>(),
                                existing
                        ));
                    }
                }
                return bean;
            }
        };
    }

    @SuppressWarnings("rawtypes")
    private static RecordInterceptor getExistingRecordInterceptor(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        Field field = ReflectionUtils.findField(factory.getClass(), "recordInterceptor");
        if (field == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(field);
        Object val = ReflectionUtils.getField(field, factory);
        return val instanceof RecordInterceptor interceptor ? interceptor : null;
    }

    public static class TraceContextProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {
        @Override
        public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
            if (record != null) {
                ExecutionKafkaTracePropagator.inject(record.headers(), Context.current());
            }
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        }

        @Override
        public void close() {
        }

        @Override
        public void configure(Map<String, ?> configs) {
        }
    }

    public static class ComposingRecordInterceptor<K, V> implements RecordInterceptor<K, V> {
        private final TraceContextRecordInterceptor<K, V> primary;
        private final RecordInterceptor<K, V> delegate;

        public ComposingRecordInterceptor(
                TraceContextRecordInterceptor<K, V> primary,
                RecordInterceptor<K, V> delegate
        ) {
            this.primary = primary;
            this.delegate = delegate;
        }

        @Override
        public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            ConsumerRecord<K, V> enriched = primary.intercept(record, consumer);
            if (enriched == null) {
                return null;
            }
            ConsumerRecord<K, V> result = delegate.intercept(enriched, consumer);
            if (result == null) {
                primary.afterRecord(enriched, consumer);
            }
            return result;
        }

        @Override
        public void success(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            delegate.success(record, consumer);
            primary.success(record, consumer);
        }

        @Override
        public void failure(ConsumerRecord<K, V> record, Exception exception, Consumer<K, V> consumer) {
            primary.failure(record, exception, consumer);
            delegate.failure(record, exception, consumer);
        }

        @Override
        public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            try {
                delegate.afterRecord(record, consumer);
            } finally {
                primary.afterRecord(record, consumer);
            }
        }

        @Override
        public void clearThreadState(Consumer<?, ?> consumer) {
            try {
                delegate.clearThreadState(consumer);
            } finally {
                primary.clearThreadState(consumer);
            }
        }
    }

    public static class TraceContextRecordInterceptor<K, V> implements RecordInterceptor<K, V> {
        private static final ThreadLocal<SpanScopeHolder> ACTIVE_SCOPE = new ThreadLocal<>();

        @Override
        public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            if (ACTIVE_SCOPE.get() != null) {
                cleanup();
            }
            if (record == null) {
                return null;
            }
            Span currentSpan = Span.current();
            if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
                // OTel Java agent (or upstream instrumentation) already owns the consumer span.
                ExecutionKafkaTracePropagator.enrichSpanFromHeaders(currentSpan, record.headers(), record.topic());
                ExecutionKafkaTracePropagator.populateMdcFromHeaders(record.headers(), Context.current());
                ACTIVE_SCOPE.set(new SpanScopeHolder(currentSpan, null, false));
                return record;
            }

            Tracer tracer = GlobalOpenTelemetry.getTracer("com.orynx.execution.kafka");
            Span span = ExecutionKafkaTracePropagator.startConsumerSpan(
                    tracer,
                    "orynx.kafka.consume." + record.topic(),
                    record.headers(),
                    record.topic()
            );
            Scope scope = span.makeCurrent();
            ExecutionKafkaTracePropagator.populateMdcFromHeaders(record.headers(), Context.current());
            ACTIVE_SCOPE.set(new SpanScopeHolder(span, scope, true));
            return record;
        }

        @Override
        public void failure(ConsumerRecord<K, V> record, Exception exception, Consumer<K, V> consumer) {
            String errorCategory = ExecutionObservabilitySanitizer.classifyErrorCategory(exception);
            MDC.put(ExecutionKafkaTracePropagator.MDC_ERROR_CATEGORY, errorCategory);
            SpanScopeHolder holder = ACTIVE_SCOPE.get();
            if (holder != null && holder.span != null && exception != null) {
                holder.span.setAttribute("error.type", exception.getClass().getSimpleName());
                holder.span.setAttribute("orynx.error.category", errorCategory);
                holder.span.setAttribute("error.message", ExecutionObservabilitySanitizer.safeErrorMessage(exception));
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
                if (holder.ownedByInterceptor) {
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
            }
            ExecutionKafkaTracePropagator.clearKafkaMdc();
        }

        private record SpanScopeHolder(Span span, Scope scope, boolean ownedByInterceptor) {}
    }
}
