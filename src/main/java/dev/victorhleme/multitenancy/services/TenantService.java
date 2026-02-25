package dev.victorhleme.multitenancy.services;

import dev.victorhleme.multitenancy.config.MultiTenantManager;
import dev.victorhleme.multitenancy.dtos.TenantDto;
import dev.victorhleme.multitenancy.entities.Tenant;
import dev.victorhleme.multitenancy.exceptions.NotFoundException;
import dev.victorhleme.multitenancy.mappers.TenantMapper;
import dev.victorhleme.multitenancy.repositories.TenantRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;


@Slf4j
@Service
public class TenantService implements SmartInitializingSingleton {

    private final TenantRepository tenantRepository;
    private final MultiTenantManager tenantManager;

    /** In-memory cache of active tenants, populated on startup and refreshed periodically. */
    private final Map<String, Tenant> activeTenants = new ConcurrentHashMap<>();

    public TenantService(
        TenantRepository tenantRepository,
        MultiTenantManager tenantManager
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantManager = tenantManager;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Called by Spring after ALL singleton beans are fully initialized — including
     * {@code tenantManagementFlyway} — so the Tenant table is guaranteed to exist.
     */
    @Override
    public void afterSingletonsInstantiated() {
        loadTenants();
    }

    @Scheduled(fixedDelayString = "${multitenancy.tenant-refresh-rate-ms:3600000}")
    public void revalidateTenants() {
        log.info("Revalidating tenant connections");
        loadTenants();
    }

    public void loadTenants() {
        Map<String, Tenant> tenants = fetchActiveTenantsFromDb();
        activeTenants.clear();
        activeTenants.putAll(tenants);

        if (tenants.isEmpty()) {
            log.warn("No active tenants found. Create tenants via the /tenant endpoint.");
            return;
        }

        log.debug("Found active tenants: {}", tenants.keySet());
        List<String> loaded = tenantManager.replaceTenants(tenants).stream()
            .map(Tenant::getCode)
            .toList();
        log.info("Datasources successfully loaded: {}", loaded);
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @Transactional(rollbackOn = Exception.class)
    public TenantDto saveTenant(TenantDto dto) {
        if (tenantRepository.findByCode(dto.getCode()).isPresent()) {
            throw new IllegalArgumentException(
                "Tenant with code %s already exists".formatted(dto.getCode()));
        }
        Tenant tenant = tenantRepository.save(TenantMapper.fromDto(dto).withActive(true));
        addDatasource(tenant);
        return TenantMapper.fromEntity(tenant);
    }

    @Transactional
    public List<TenantDto> getTenants() {
        return tenantRepository.findAll()
            .stream()
            .map(TenantMapper::fromEntity)
            .toList();
    }

    @Transactional(rollbackOn = Exception.class)
    public void enableTenant(String code) {
        tenantRepository.enableTenant(code);
        addDatasource(findTenantByCodeOrThrow(code));
    }

    @Transactional(rollbackOn = Exception.class)
    public void disableTenant(String code) {
        tenantRepository.disableTenant(code);
        removeDatasource(findTenantByCodeOrThrow(code));
    }

    // -------------------------------------------------------------------------
    // Tenant context
    // -------------------------------------------------------------------------

    /**
     * Executes {@code action} within the scope of the given tenant.
     *
     * <p>The tenant's datasource and timezone are active for the duration of the call.
     * Uses {@link MultiTenantManager#CURRENT_TENANT} ({@link ScopedValue}, JEP 506)
     * internally, so this method is safe to use with virtual threads and structured
     * concurrency. The scope is automatically exited when {@code action} returns.
     *
     * @param tenantCode code of an active tenant
     * @param action     work to perform in the tenant's context
     * @throws IllegalStateException if the tenant is not in the active-tenants cache
     */
    public void runWithTenant(String tenantCode, Runnable action) {
        Tenant tenant = activeTenants.get(tenantCode);
        if (tenant == null) {
            throw new IllegalStateException("Tenant [%s] is not active".formatted(tenantCode));
        }
        tenantManager.runWithTenant(tenant, action);
    }

    /**
     * Iterates over all active tenants, executing {@code consumer} in each tenant's context.
     * Uses {@link #runWithTenant} so the datasource and timezone are properly scoped.
     */
    public void iterateOverTenants(Consumer<Tenant> consumer) {
        log.info("Iterating over Tenants");
        tenantRepository.findAllByActive(true).forEach(tenant -> {
            if (!activeTenants.containsKey(tenant.getCode())) {
                log.warn("Tenant [{}] not in active cache, skipping iteration", tenant.getCode());
                return;
            }
            log.info("Executing action for tenant [{}]", tenant.getCode());
            runWithTenant(tenant.getCode(), () -> consumer.accept(tenant));
        });
    }

    public boolean tenantExistsByActive(String tenantCode, Boolean active) {
        log.debug("Checking if tenant [{}] exists with active={}", tenantCode, active);
        return tenantRepository.findByCode(tenantCode)
            .filter(t -> active == null || t.isActive() == active)
            .isPresent();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void addDatasource(Tenant tenant) {
        String code = tenant.getCode();
        log.debug("Adding datasource for tenant [{}]", code);
        if (tenantManager.addTenant(tenant) == null) {
            log.warn("Failed to provision datasource for tenant [{}]", code);
            return;
        }
        log.debug("Datasource loaded for tenant [{}]", code);
        activeTenants.put(code, tenant);
    }

    private void removeDatasource(Tenant tenant) {
        log.debug("Removing datasource for tenant [{}]", tenant.getCode());
        tenantManager.removeTenant(tenant);
        activeTenants.remove(tenant.getCode());
    }

    private Map<String, Tenant> fetchActiveTenantsFromDb() {
        log.debug("Loading active tenants from database");
        return tenantRepository.findAllByActive(true).stream()
            .collect(Collectors.toMap(Tenant::getCode, t -> t));
    }

    private Tenant findTenantByCodeOrThrow(String code) {
        return tenantRepository.findByCode(code)
            .orElseThrow(() -> new NotFoundException(Tenant.class, code));
    }

}
