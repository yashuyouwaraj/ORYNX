FROM busybox:1.36.1-musl AS busybox
FROM otel/opentelemetry-collector-contrib:0.114.0
COPY --from=busybox /bin/busybox /bin/busybox
