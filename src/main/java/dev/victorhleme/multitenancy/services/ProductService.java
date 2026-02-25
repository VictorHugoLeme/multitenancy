package dev.victorhleme.multitenancy.services;

import dev.victorhleme.multitenancy.dtos.ProductDto;
import dev.victorhleme.multitenancy.entities.Product;
import dev.victorhleme.multitenancy.mappers.ProductMapper;
import dev.victorhleme.multitenancy.repositories.ProductRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final TenantService tenantService;

    public ProductService(ProductRepository productRepository, TenantService tenantService) {
        this.productRepository = productRepository;
        this.tenantService = tenantService;
    }

    // -------------------------------------------------------------------------
    // CRUD (or CR...)
    // -------------------------------------------------------------------------

    @Transactional(rollbackOn = Exception.class)
    public ProductDto saveProduct(ProductDto dto) {
        Product product = productRepository.save(ProductMapper.fromDto(dto));
        return ProductMapper.fromEntity(product);
    }

    @Transactional
    public List<ProductDto> getProducts() {
        return productRepository.findAll()
            .stream()
            .map(ProductMapper::fromEntity)
            .toList();
    }

    public long countAllTenantProducts() {
        AtomicLong total = new AtomicLong();
        tenantService.iterateOverTenants(_ -> {
            log.info("Counting products for tenant");
            total.addAndGet(productRepository.count());
        });
        return total.get();
    }
}
