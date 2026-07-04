package com.techdesksystem.techdesk.tenant.controller;

import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.dto.TenantResponse;
import com.techdesksystem.techdesk.tenant.dto.TenantStatusUpdateRequest;
import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import com.techdesksystem.techdesk.tenant.service.TenantManagementService;
import com.techdesksystem.techdesk.tenant.service.TenantProvisioningService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantController {

    private final TenantProvisioningService provisioningService;
    private final TenantManagementService managementService;

    public TenantController(
            TenantProvisioningService provisioningService,
            TenantManagementService managementService
    ) {
        this.provisioningService = provisioningService;
        this.managementService = managementService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        return provisioningService.provision(request);
    }

    @GetMapping
    public Page<TenantResponse> list(
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) String query,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        return managementService.list(status, query, pageable);
    }

    @PatchMapping("/{tenantId}/status")
    public TenantResponse updateStatus(
            @PathVariable UUID tenantId,
            @Valid @RequestBody TenantStatusUpdateRequest request
    ) {
        return managementService.updateStatus(tenantId, request.status());
    }
}
