package dev.victorhleme.multitenancy.repositories;

import dev.victorhleme.multitenancy.entities.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    List<Tenant> findAllByActive(boolean active);

    Optional<Tenant> findByCode(String code);

    @Modifying
    @Query("UPDATE Tenant t SET t.active = true WHERE t.code = :code")
    void enableTenant(@Param("code") String code);

    @Modifying
    @Query("UPDATE Tenant t SET t.active = false WHERE t.code = :code")
    void disableTenant(@Param("code") String code);
}
