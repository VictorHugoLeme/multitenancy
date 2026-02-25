package dev.victorhleme.multitenancy.entities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@With
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column
    private String code;
    @Column
    private String name;
    @Column
    private boolean active = true;

}
