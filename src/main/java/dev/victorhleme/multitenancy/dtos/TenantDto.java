package dev.victorhleme.multitenancy.dtos;

import lombok.*;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class TenantDto {
    private Long id;
    private String name;
    private String code;
    private boolean active;

    public TenantDto(String name, String code) {
        this.name = name;
        this.code = code;
    }
}