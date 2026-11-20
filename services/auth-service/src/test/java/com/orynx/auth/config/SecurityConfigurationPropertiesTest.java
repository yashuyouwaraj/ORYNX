package com.orynx.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationPropertiesTest {

    @Test
    @DisplayName("auth-service application.properties must bind database credentials and JWT secret from environment")
    void authServicePropertiesMustUseEnvironmentPlaceholders() throws IOException {
        Properties properties = loadPropertiesFromResource("/application.properties");

        assertThat(properties.getProperty("spring.datasource.username"))
                .isEqualTo("${SPRING_DATASOURCE_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .isEqualTo("${SPRING_DATASOURCE_PASSWORD}");
        assertThat(properties.getProperty("jwt.secret"))
                .isEqualTo("${JWT_SECRET}");
        assertThat(properties.getProperty("jwt.expiration"))
                .isEqualTo("${JWT_EXPIRATION:3600000}");
    }

    @Test
    @DisplayName("All microservices must not contain plaintext database passwords in committed properties")
    void microservicesPropertiesMustNotContainPlaintextPasswords() throws IOException {
        Path projectRoot = Path.of("../..").toAbsolutePath().normalize();
        Path[] propertyFiles = new Path[]{
                projectRoot.resolve("services/auth-service/src/main/resources/application.properties"),
                projectRoot.resolve("services/orchestrator-service/src/main/resources/application.properties"),
                projectRoot.resolve("services/execution-service/src/main/resources/application.properties")
        };

        for (Path path : propertyFiles) {
            if (Files.exists(path)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }

                String password = props.getProperty("spring.datasource.password");
                assertThat(password)
                        .as("File %s must externalize spring.datasource.password", path.getFileName())
                        .isEqualTo("${SPRING_DATASOURCE_PASSWORD}");

                String username = props.getProperty("spring.datasource.username");
                assertThat(username)
                        .as("File %s must externalize spring.datasource.username", path.getFileName())
                        .isEqualTo("${SPRING_DATASOURCE_USERNAME}");
            }
        }
    }

    private Properties loadPropertiesFromResource(String resourceName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(resourceName)) {
            assertThat(in).as("Resource %s must exist on classpath", resourceName).isNotNull();
            properties.load(in);
        }
        return properties;
    }
}
