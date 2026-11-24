FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests \
    && find target -maxdepth 1 -name '*.jar' ! -name '*.original' -exec cp {} /build/app.jar \;

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S orynx && adduser -S -G orynx orynx

WORKDIR /app

ADD --chown=orynx:orynx https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.11.0/opentelemetry-javaagent-2.11.0.jar /app/opentelemetry-javaagent.jar
RUN echo "4cff4ab46179260a61fc0d884f3f170cfbd9d2962dd260be2cff31262d0c7618  /app/opentelemetry-javaagent.jar" | sha256sum -c -
COPY --from=builder --chown=orynx:orynx /build/app.jar /app/app.jar

USER orynx:orynx

EXPOSE 8081 9100

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "AGENT_OPT=''; if [ \"${OTEL_JAVAAGENT_ENABLED:-false}\" = \"true\" ]; then AGENT_OPT='-javaagent:/app/opentelemetry-javaagent.jar -Dotel.javaagent.logging=none'; fi; exec java ${AGENT_OPT} ${JAVA_OPTS} -jar /app/app.jar"]
