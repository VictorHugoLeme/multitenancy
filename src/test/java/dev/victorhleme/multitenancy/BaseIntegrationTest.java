package dev.victorhleme.multitenancy;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests.
 *
 * <p>Starts a single MySQL 8.4 container for the entire test class lifecycle and overrides
 * all datasource-related properties so both Spring's managed datasource and
 * {@code MultiTenantManager}'s per-tenant datasources point to the container.
 *
 * <p>Root credentials are used so the app user can execute {@code CREATE DATABASE db_*}
 * when provisioning tenant databases at runtime (equivalent to the grant in
 * {@code docker/mysql/01_grant_privileges.sql}).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class BaseIntegrationTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("db_tenants")
            .withUsername("root")
            .withPassword("root");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String settings = "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&createDatabaseIfNotExist=true";
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://%s:%d/db_tenants?%s".formatted(mysql.getHost(), mysql.getMappedPort(3306), settings));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
        registry.add("multitenancy.db.host", mysql::getHost);
        registry.add("multitenancy.db.port", () -> String.valueOf(mysql.getMappedPort(3306)));
        registry.add("multitenancy.db.settings", () -> settings);
    }
}
