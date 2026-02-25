package dev.victorhleme.multitenancy.mappers;

import dev.victorhleme.multitenancy.dtos.ProductDto;
import dev.victorhleme.multitenancy.entities.Product;
import org.springframework.beans.BeanUtils;

public class ProductMapper {
    public static Product fromDto(final ProductDto dto) {
        final Product entity = new Product();
        BeanUtils.copyProperties(dto, entity, "id");
        return entity;
    }

    public static ProductDto fromEntity(final Product entity) {
        final ProductDto dto = new ProductDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
