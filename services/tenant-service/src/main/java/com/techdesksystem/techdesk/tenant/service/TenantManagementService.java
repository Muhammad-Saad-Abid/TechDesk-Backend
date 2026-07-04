package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.dto.TenantResponse;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import com.techdesksystem.techdesk.tenant.exception.TenantException;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TenantManagementService {

    private final TenantRepository tenantRepository;

    public TenantManagementService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public Page<TenantResponse> list(
            TenantStatus status,
            String query,
            Pageable pageable
    ) {
        Specification<Tenant> filters = (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }

            if (query != null && !query.isBlank()) {
                String pattern = "%"
                        + query.trim().toLowerCase(Locale.ROOT)
                        + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern),
                        builder.like(builder.lower(root.get("slug")), pattern)
                ));
            }

            return builder.and(predicates.toArray(Predicate[]::new));
        };

        return tenantRepository.findAll(filters, pageable)
                .map(TenantResponse::from);
    }

    @Transactional
    public TenantResponse updateStatus(UUID tenantId, TenantStatus status) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(TenantException::notFound);
        tenant.setStatus(status);
        return TenantResponse.from(tenantRepository.save(tenant));
    }
}
