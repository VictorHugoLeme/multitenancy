package dev.victorhleme.multitenancy.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.victorhleme.multitenancy.entities.Tenant;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages the routing data source for multi-tenant applications.
 *
 * <p>Initializes a management datasource that stores tenant metadata, then—based on active
 * tenants—creates per-tenant datasources and runs Flyway migrations for each one.
 *
 * <p>Tenant context is propagated via {@link ScopedValue} (JEP 506, finalized in Java 25),
 * which is immutable and safe for use with virtual threads. Callers must use
 * {@link #runWithTenant(Tenant, Runnable)} to bind a tenant scope; this replaces the
 * mutable set/clear ThreadLocal pattern.
 */
@Slf4j
@Configuration
public class MultiTenantManager {

    /**
     * Scoped value carrying the current tenant code for the duration of a structured scope.
     * Read by {@link AbstractRoutingDataSource} to select the correct datasource.
     */
    public static final ScopedValue<String> CURRENT_TENANT = ScopedValue.newInstance();

    // --- Migration constants ---

    private static final String TENANT_MANAGEMENT_DB_SUFFIX = "tenants";

    private static final String DB_NAME_PATTERN = "db_%s";

    private static final String MANAGEMENT_TABLE     = "FlywayMultiTenancySchemaHistory";
    private static final String MANAGEMENT_LOCATIONS = "classpath:db/migration/tenantManagement/mysql";

    private static final String COMMONS_TABLE     = "FlywayCommonsSchemaHistory";
    private static final String COMMONS_LOCATIONS = "classpath:db/migration/commons/mysql";

    // --- Configuration ---

    /** Locations for per-tenant application migrations (e.g. classpath:db/migration). */
    @Value("${multitenancy.app-locations:#{null}}")
    private String appLocations;

    /** Flyway schema history table name used for per-tenant application migrations. */
    @Value("${multitenancy.app-migration-table:flyway_schema_history}")
    private String appMigrationTable;

    @Value("${multitenancy.db.host:localhost}")
    private String host;

    @Value("${multitenancy.db.port:3306}")
    private String port;

    @Value("${multitenancy.db.settings:#{null}}")
    private String settings;

    @Value("${multitenancy.parallel-migration:true}")
    private boolean parallelMigration;

    // --- State ---

    private final DataSourceProperties properties;
    private final Environment environment;
    private final Map<Object, Object> tenantDataSources = new ConcurrentHashMap<>();

    private AbstractRoutingDataSource multiTenantDataSource;
    private DataSource tenantManagementDatasource;

    public MultiTenantManager(DataSourceProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    // -------------------------------------------------------------------------
    // Spring beans
    // -------------------------------------------------------------------------

    /**
     * Primary datasource: an {@link AbstractRoutingDataSource} that delegates to the
     * per-tenant datasource bound in the current {@link #CURRENT_TENANT} scope, or
     * falls back to the management datasource when no tenant is active.
     */
    @Primary
    @Bean
    public DataSource dataSource() {
        log.debug("Multitenancy enabled — initializing routing datasource");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        tenantManagementDatasource = createDataSource(dbNameFor(TENANT_MANAGEMENT_DB_SUFFIX));

        multiTenantDataSource = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return CURRENT_TENANT.isBound() ? CURRENT_TENANT.get() : null;
            }
        };
        multiTenantDataSource.setDefaultTargetDataSource(tenantManagementDatasource);
        multiTenantDataSource.setTargetDataSources(tenantDataSources);
        multiTenantDataSource.afterPropertiesSet();

        return multiTenantDataSource;
    }

    /**
     * Runs Flyway migrations for the management database (creates the Tenant table etc.).
     * Spring Boot's auto-configured Flyway is disabled so this bean is the sole
     * database initializer; TenantService defers its @PostConstruct via
     * SmartInitializingSingleton to ensure this runs first.
     */
    @Bean
    @DependsOn("dataSource")
    public Flyway tenantManagementFlyway() {
        return migrateFlyway(MANAGEMENT_LOCATIONS, MANAGEMENT_TABLE, null, tenantManagementDatasource);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Replaces the active tenant datasource map with the given set.
     * Datasources for tenants no longer present are closed. New tenants are
     * provisioned (datasource created + migrations run).
     *
     * @return tenants for which a valid connection was established
     */
    public List<Tenant> replaceTenants(Map<String, Tenant> tenants) {
        tenantDataSources.keySet().removeIf(key -> {
            String code = (String) key;
            if (!tenants.containsKey(code)) {
                closeDataSource((DataSource) tenantDataSources.get(code));
                log.info("Closed and removed datasource for tenant [{}]", code);
                return true;
            }
            return false;
        });

        if (parallelMigration && tenants.size() > 1) {
            return migrateInParallel(tenants);
        }

        return tenants.values().stream()
            .map(this::addTenant)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Provisions a datasource for a single tenant and runs its migrations.
     * No-ops if the datasource already exists.
     *
     * @return the tenant if the connection is valid, {@code null} on failure
     */
    public Tenant addTenant(Tenant tenant) {
        if (tenant == null) {
            log.warn("Tenant is null — skipping datasource creation");
            return null;
        }

        String code = tenant.getCode();
        if (tenantDataSources.containsKey(code)) {
            log.debug("Datasource for tenant [{}] already exists, reusing", code);
            return tenant;
        }

        try {
            DataSource datasource = configureTenantDatasource(tenant);
            return validateConnection(datasource, tenant);
        } catch (Exception e) {
            log.error("Failed to add tenant [{}]", code, e);
            return null;
        }
    }

    /**
     * Removes and closes the datasource for the given tenant.
     */
    public void removeTenant(Tenant tenant) {
        String code = tenant.getCode();
        DataSource datasource = (DataSource) tenantDataSources.remove(code);
        if (datasource != null) {
            closeDataSource(datasource);
            log.info("Removed datasource for tenant [{}]", code);
        }
    }

    /**
     * Executes {@code action} within the scope of the given tenant.
     *
     * <p>Binds {@link #CURRENT_TENANT} via {@link ScopedValue} so that the
     * routing datasource resolves to this tenant's database for the duration of
     * the call. The scope is automatically exited when {@code action} returns,
     * regardless of exceptions.
     *
     * @param tenant the tenant whose datasource should be active
     * @param action the work to execute in the tenant's context
     */
    public void runWithTenant(Tenant tenant, Runnable action) {
        String code = tenant.getCode();
        MDC.put("tenantCode", "[%s]".formatted(code));
        log.debug("Executing action in tenant context [{}]", code);
        try {
            ScopedValue.where(CURRENT_TENANT, code).run(action);
        } finally {
            MDC.remove("tenantCode");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Provisions tenant datasources concurrently using virtual threads.
     * Each provisioning call is independent; failures for individual tenants
     * are logged and excluded from the result rather than aborting the batch.
     */
    private List<Tenant> migrateInParallel(Map<String, Tenant> tenants) {
        log.debug("Starting parallel migration for {} tenants", tenants.size());
        // ExecutorService.close() (AutoCloseable since Java 19) waits for all tasks
        // before shutting down — safe to use in try-with-resources.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Tenant>> futures = tenants.values().stream()
                .map(tenant -> CompletableFuture.supplyAsync(() -> addTenant(tenant), executor))
                .toList();
            return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
        }
    }

    private DataSource configureTenantDatasource(Tenant tenant) {
        String code = tenant.getCode();
        log.info("Creating datasource for tenant [{}]", code);

        DataSource datasource = createDataSource(dbNameFor(code));
        tenantDataSources.put(code, datasource);
        multiTenantDataSource.afterPropertiesSet();

        if (appLocations != null) {
            log.debug("Running application migrations at {} for tenant [{}]", appLocations, code);
            migrateFlyway(appLocations, appMigrationTable, code, datasource);
        }

        log.debug("Running commons migrations at {} for tenant [{}]", COMMONS_LOCATIONS, code);
        migrateFlyway(COMMONS_LOCATIONS, COMMONS_TABLE, code, datasource);

        return datasource;
    }

    private Tenant validateConnection(DataSource datasource, Tenant tenant) {
        try (var ignored = datasource.getConnection()) {
            return tenant;
        } catch (SQLException e) {
            log.error("Could not establish connection for tenant [{}]: {}", tenant.getCode(), e.getMessage());
            return null;
        }
    }

    private void closeDataSource(DataSource dataSource) {
        // Pattern-matching switch (GA since Java 21)
        switch (dataSource) {
            case HikariDataSource hds -> {
                hds.close();
                log.info("Closed HikariCP datasource");
            }
            default ->
                log.warn("Cannot close datasource of type {}", dataSource.getClass().getSimpleName());
        }
    }

    private DataSource createDataSource(String dbName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(dbName));
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setMinimumIdle(2);
        config.setMaximumPoolSize(20);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setConnectionTestQuery("SELECT 1");
        log.debug("Initializing HikariCP pool for database [{}]", dbName);
        return new HikariDataSource(config);
    }

    private Flyway migrateFlyway(String locations, String table, String tenantId, DataSource dataSource) {
        String context = tenantId != null ? "tenant [%s]".formatted(tenantId) : "management";
        Flyway flyway = Flyway.configure()
            .locations(locations)
            .table(table)
            .dataSource(dataSource)
            .baselineVersion("0")
            .baselineOnMigrate(true)
            .validateOnMigrate(false)
            .load();
        try {
            log.info("Running migrations for {}", context);
            flyway.migrate();
        } catch (FlywayException e) {
            log.error("Migration failed for {}, attempting repair", context, e);
            flyway.repair();
            flyway.migrate();
        }
        return flyway;
    }

    private String buildJdbcUrl(String dbName) {
        if (List.of(environment.getActiveProfiles()).contains("test")) {
            return "jdbc:h2:mem:%s%s".formatted(dbName, settings != null ? ";" + settings : "");
        }
        return "jdbc:mysql://%s:%s/%s%s".formatted(host, port, dbName, settings != null ? "?" + settings : "");
    }

    private String dbNameFor(String suffix) {
        return DB_NAME_PATTERN.formatted(suffix.toLowerCase());
    }
}
