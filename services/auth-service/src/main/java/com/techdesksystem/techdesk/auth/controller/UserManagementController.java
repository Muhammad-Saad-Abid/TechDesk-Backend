package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.dto.UserCreateRequest;
import com.techdesksystem.techdesk.auth.dto.UserInvitationRequest;
import com.techdesksystem.techdesk.auth.dto.UserPermissionsResponse;
import com.techdesksystem.techdesk.auth.dto.UserResponse;
import com.techdesksystem.techdesk.auth.dto.UserRoleAssignmentRequest;
import com.techdesksystem.techdesk.auth.dto.UserUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.UserStatus;
import com.techdesksystem.techdesk.auth.security.RequiresPermission;
import com.techdesksystem.techdesk.auth.service.UserManagementService;
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

@Validated
@RestController
@RequestMapping("/api/users")
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(
            UserManagementService userManagementService
    ) {
        this.userManagementService = userManagementService;
    }

    @PostMapping
    @RequiresPermission("users:create")
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(userManagementService.createUser(request));
    }

    @PostMapping("/invitations")
    @RequiresPermission("users:invite")
    public ResponseEntity<UserResponse> invite(
            @Valid @RequestBody UserInvitationRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(userManagementService.inviteUser(request));
    }

    @GetMapping
    @RequiresPermission("users:read")
    public Page<UserResponse> list(
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String primaryRole,
            @RequestParam(required = false) @Positive Long departmentId,
            @RequestParam(required = false, name = "q") String search,
            Pageable pageable
    ) {
        return userManagementService.listUsers(
                status,
                primaryRole,
                departmentId,
                search,
                pageable
        );
    }

    @GetMapping("/{id}")
    @RequiresPermission("users:read")
    public UserResponse get(
            @PathVariable("id") @Positive Long userId
    ) {
        return userManagementService.getUser(userId);
    }

    @PatchMapping("/{id}")
    @RequiresPermission("users:update")
    public UserResponse update(
            @PathVariable("id") @Positive Long userId,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return userManagementService.updateUser(userId, request);
    }

    @PutMapping("/{id}/roles")
    @RequiresPermission("roles:assign")
    public UserResponse assignRoles(
            @PathVariable("id") @Positive Long userId,
            @Valid @RequestBody UserRoleAssignmentRequest request
    ) {
        return userManagementService.assignRoles(userId, request);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("users:delete")
    public ResponseEntity<Void> disable(
            @PathVariable("id") @Positive Long userId
    ) {
        userManagementService.disableUser(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/permissions")
    @RequiresPermission("users:permissions:read")
    public UserPermissionsResponse permissions(
            @PathVariable("id") @Positive Long userId
    ) {
        return userManagementService.permissionsForUser(userId);
    }
}
