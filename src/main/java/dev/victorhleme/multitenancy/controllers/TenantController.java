package dev.victorhleme.multitenancy.controllers;

import dev.victorhleme.multitenancy.dtos.TenantDto;
import dev.victorhleme.multitenancy.services.ProductService;
import dev.victorhleme.multitenancy.services.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final ProductService productService;

    @PostMapping
    public ResponseEntity<?> save(@RequestBody final TenantDto dto) {
        log.info("Saving Tenant: {}", dto);
        TenantDto newTenant = tenantService.saveTenant(dto);
        return ResponseEntity.ok(newTenant);
    }

    @GetMapping
    public ResponseEntity<List<TenantDto>> findAll() {
        log.info("Getting Tenants");
        return ResponseEntity.ok(tenantService.getTenants());
    }

    @PatchMapping("/enable/{code}")
    public ResponseEntity<?> enable(
        @PathVariable String code
    ) {
        log.info("Enabling Tenant: {}", code);
        tenantService.enableTenant(code);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/disable/{code}")
    public ResponseEntity<?> disable(
        @PathVariable String code
    ) {
        log.info("Disabling Tenant: {}", code);
        tenantService.disableTenant(code);
        return ResponseEntity.noContent().build();
    }


    // Use after enabling/disabling tenant directly in the database.
    @PostMapping("/revalidate-datasources")
    public ResponseEntity<?> revalidateTenants() {
        tenantService.revalidateTenants();
        return ResponseEntity.noContent().build();
    }

}
