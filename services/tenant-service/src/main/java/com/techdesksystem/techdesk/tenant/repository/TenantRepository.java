package com.techdesksystem.techdesk.tenant.repository;

import com.techdesksystem.techdesk.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;

public interface TenantRepository extends
        JpaRepository<Tenant, UUID>,
        JpaSpecificationExecutor<Tenant> {

    boolean existsBySlug(String slug);

    boolean existsBySchemaName(String schemaName);

    List<Tenant> findByProvisioningStatusAndCreatedAtBefore(
            ProvisioningStatus provisioningStatus,
            Instant createdBefore
    );
}
