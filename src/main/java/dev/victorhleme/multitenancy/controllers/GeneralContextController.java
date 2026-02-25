package dev.victorhleme.multitenancy.controllers;

import dev.victorhleme.multitenancy.services.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v1/general")
@RequiredArgsConstructor
public class GeneralContextController {

    private final ProductService productService;

    @GetMapping("/products/count")
    public ResponseEntity<Long> countAllProducts() {
        return ResponseEntity.ok(productService.countAllTenantProducts());
    }
}
