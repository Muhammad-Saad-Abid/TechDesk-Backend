package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.dto.PermissionResponse;
import com.techdesksystem.techdesk.auth.dto.RoleCreateRequest;
import com.techdesksystem.techdesk.auth.dto.RolePermissionsUpdateRequest;
import com.techdesksystem.techdesk.auth.dto.RoleResponse;
import com.techdesksystem.techdesk.auth.dto.RoleUpdateRequest;
import com.techdesksystem.techdesk.auth.security.RequiresPermission;
import com.techdesksystem.techdesk.auth.service.RoleManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class RoleManagementController {

    private final RoleManagementService roleManagementService;

    public RoleManagementController(
            RoleManagementService roleManagementService
    ) {
        this.roleManagementService = roleManagementService;
    }

    @PostMapping("/api/roles")
    @RequiresPermission("roles:create")
    public ResponseEntity<RoleResponse> createRole(
            @Valid @RequestBody RoleCreateRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(roleManagementService.createRole(request));
    }

    @GetMapping("/api/roles")
    @RequiresPermission("roles:read")
    public Page<RoleResponse> listRoles(
            @RequestParam(required = false) Boolean systemRole,
            @RequestParam(required = false, name = "q") String search,
            Pageable pageable
    ) {
        return roleManagementService.listRoles(systemRole, search, pageable);
    }

    @GetMapping("/api/roles/{roleId}")
    @RequiresPermission("roles:read")
    public RoleResponse getRole(
            @Positive @PathVariable Long roleId
    ) {
        return roleManagementService.getRole(roleId);
    }

    @PatchMapping("/api/roles/{roleId}")
    @RequiresPermission("roles:update")
    public RoleResponse updateRole(
            @Positive @PathVariable Long roleId,
            @Valid @RequestBody RoleUpdateRequest request
    ) {
        return roleManagementService.updateRole(roleId, request);
    }

    @PutMapping("/api/roles/{roleId}/permissions")
    @RequiresPermission("roles:assign")
    public RoleResponse updateRolePermissions(
            @Positive @PathVariable Long roleId,
            @Valid @RequestBody RolePermissionsUpdateRequest request
    ) {
        return roleManagementService.updateRolePermissions(roleId, request);
    }

    @DeleteMapping("/api/roles/{roleId}")
    @RequiresPermission("roles:delete")
    public ResponseEntity<Void> deleteRole(
            @Positive @PathVariable Long roleId
    ) {
        roleManagementService.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/permissions")
    @RequiresPermission("roles:read")
    public Page<PermissionResponse> listPermissions(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, name = "q") String search,
            Pageable pageable
    ) {
        return roleManagementService.listPermissions(
                category,
                search,
                pageable
        );
    }
}
