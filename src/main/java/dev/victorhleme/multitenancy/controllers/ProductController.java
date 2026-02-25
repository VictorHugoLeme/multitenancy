package dev.victorhleme.multitenancy.controllers;

import dev.victorhleme.multitenancy.dtos.ProductDto;
import dev.victorhleme.multitenancy.services.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<?> save(@RequestBody final ProductDto dto) {
        log.info("Saving Product: {}", dto);
        ProductDto newProduct = productService.saveProduct(dto);
        return ResponseEntity.ok(newProduct);
    }

    @GetMapping
    public ResponseEntity<List<ProductDto>> findAll() {
        log.info("Getting Products");
        return ResponseEntity.ok(productService.getProducts());
    }

}
