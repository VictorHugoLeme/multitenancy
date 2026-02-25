package dev.victorhleme.multitenancy.mappers;

import dev.victorhleme.multitenancy.dtos.TenantDto;
import dev.victorhleme.multitenancy.entities.Tenant;
import org.springframework.beans.BeanUtils;

public class TenantMapper {
    public static Tenant fromDto(final TenantDto dto) {
        final Tenant entity = new Tenant();
        BeanUtils.copyProperties(dto, entity);
        return entity;
    }

    public static TenantDto fromEntity(final Tenant entity) {
        final TenantDto dto = new TenantDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
