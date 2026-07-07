package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.dto.PermissionResponse;
import com.techdesksystem.techdesk.auth.dto.RoleCreateRequest;
import com.techdesksystem.techdesk.auth.dto.RolePermissionsUpdateRequest;
import com.techdesksystem.techdesk.auth.dto.RoleResponse;
import com.techdesksystem.techdesk.auth.dto.RoleUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.Permission;
import com.techdesksystem.techdesk.auth.entity.Role;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.PermissionRepository;
import com.techdesksystem.techdesk.auth.repository.RoleRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoleManagementService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleManagementService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository
    ) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * Creates a custom tenant-local role and atomically attaches the requested
     * permission set after validating every permission code exists.
     */
    @Transactional
    public RoleResponse createRole(RoleCreateRequest request) {
        String roleName = normalizeRequiredRoleName(request.name());
        if (roleRepository.existsByName(roleName)) {
            throw AuthException.conflict(
                    "ROLE_NAME_ALREADY_EXISTS",
                    "Role name already exists: " + roleName
            );
        }

        Set<String> permissionCodes = validatePermissionCodes(
                request.permissionCodes()
        );

        Role role = new Role();
        role.setName(roleName);
        role.setDisplayName(normalizeRequiredText(
                request.displayName(),
                "INVALID_ROLE_DISPLAY_NAME",
                "Role display name is required."
        ));
        role.setDescription(normalizeRequiredText(
                request.description(),
                "INVALID_ROLE_DESCRIPTION",
                "Role description is required."
        ));
        role.setSystemRole(false);

        Role savedRole = roleRepository.save(role);
        replacePermissions(savedRole.getId(), permissionCodes);

        return toResponse(savedRole);
    }

    /**
     * Lists roles with optional system/custom filtering and lightweight search
     * over role name, display name, and description.
     */
    @Transactional(readOnly = true)
    public Page<RoleResponse> listRoles(
            Boolean systemRole,
            String search,
            Pageable pageable
    ) {
        return roleRepository.findAll(
                roleFilters(systemRole, search),
                pageable
        ).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public RoleResponse getRole(Long roleId) {
        return toResponse(findRole(roleId));
    }

    /**
     * Lists seeded tenant-local permissions. Permissions are managed by
     * migrations, while roles attach subsets of them.
     */
    @Transactional(readOnly = true)
    public Page<PermissionResponse> listPermissions(
            String category,
            String search,
            Pageable pageable
    ) {
        return permissionRepository.findAll(
                permissionFilters(category, search),
                pageable
        ).map(this::toResponse);
    }

    /**
     * Updates mutable custom role metadata and, when provided, its permission
     * set. System roles are protected from tenant-level mutation.
     */
    @Transactional
    public RoleResponse updateRole(
            Long roleId,
            RoleUpdateRequest request
    ) {
        if (!hasAnyRoleUpdate(request)) {
            throw AuthException.badRequest(
                    "NO_ROLE_UPDATES",
                    "At least one role field must be provided."
            );
        }

        Role role = findRole(roleId);
        requireCustomRole(role, "updated");

        if (request.displayName() != null) {
            role.setDisplayName(normalizeRequiredText(
                    request.displayName(),
                    "INVALID_ROLE_DISPLAY_NAME",
                    "Role display name is required."
            ));
        }

        if (request.description() != null) {
            role.setDescription(normalizeRequiredText(
                    request.description(),
                    "INVALID_ROLE_DESCRIPTION",
                    "Role description is required."
            ));
        }

        if (request.permissionCodes() != null) {
            replacePermissions(
                    roleId,
                    validatePermissionCodes(request.permissionCodes())
            );
        }

        return toResponse(role);
    }

    /**
     * Replaces a custom role's permission set through the explicit assignment
     * endpoint used by Company Admin role management screens.
     */
    @Transactional
    public RoleResponse updateRolePermissions(
            Long roleId,
            RolePermissionsUpdateRequest request
    ) {
        Role role = findRole(roleId);
        requireCustomRole(role, "assigned permissions");

        replacePermissions(
                roleId,
                validatePermissionCodes(request.permissionCodes())
        );

        return toResponse(role);
    }

    /**
     * Deletes a custom role only when no users are currently assigned to it.
     */
    @Transactional
    public void deleteRole(Long roleId) {
        Role role = findRole(roleId);
        requireCustomRole(role, "deleted");

        long assignedUsers = roleRepository.countUserAssignments(roleId);
        if (assignedUsers > 0) {
            throw AuthException.conflict(
                    "ROLE_IN_USE",
                    "Role cannot be deleted while users are assigned to it."
            );
        }

        roleRepository.deletePermissionAssignments(roleId);
        roleRepository.delete(role);
    }

    private Role findRole(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> AuthException.notFound(
                        "ROLE_NOT_FOUND",
                        "Role was not found."
                ));
    }

    private void replacePermissions(
            Long roleId,
            Set<String> permissionCodes
    ) {
        roleRepository.deletePermissionAssignments(roleId);
        permissionCodes.forEach(permissionCode ->
                roleRepository.grantPermission(roleId, permissionCode)
        );
    }

    private Set<String> validatePermissionCodes(
            Set<String> permissionCodes
    ) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return Set.of();
        }

        Set<String> normalized = permissionCodes.stream()
                .map(this::normalizeRequiredPermissionCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> foundCodes = permissionRepository
                .findByCodeIn(normalized)
                .stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        List<String> unknownCodes = normalized.stream()
                .filter(code -> !foundCodes.contains(code))
                .toList();

        if (!unknownCodes.isEmpty()) {
            throw AuthException.badRequest(
                    "UNKNOWN_PERMISSION_CODE",
                    "Unknown permission code: " + unknownCodes.get(0)
            );
        }

        return normalized;
    }

    private Specification<Role> roleFilters(
            Boolean systemRole,
            String search
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (systemRole != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("systemRole"),
                        systemRole
                ));
            }

            if (StringUtils.hasText(search)) {
                String pattern = "%"
                        + search.trim().toLowerCase()
                        + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("name")),
                                pattern
                        ),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("displayName")),
                                pattern
                        ),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("description")),
                                pattern
                        )
                ));
            }

            return criteriaBuilder.and(
                    predicates.toArray(Predicate[]::new)
            );
        };
    }

    private Specification<Permission> permissionFilters(
            String category,
            String search
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(category)) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.lower(root.get("category")),
                        category.trim().toLowerCase()
                ));
            }

            if (StringUtils.hasText(search)) {
                String pattern = "%"
                        + search.trim().toLowerCase()
                        + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("code")),
                                pattern
                        ),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("description")),
                                pattern
                        )
                ));
            }

            return criteriaBuilder.and(
                    predicates.toArray(Predicate[]::new)
            );
        };
    }

    private boolean hasAnyRoleUpdate(RoleUpdateRequest request) {
        return request.displayName() != null
                || request.description() != null
                || request.permissionCodes() != null;
    }

    private String normalizeRequiredRoleName(String roleName) {
        String normalized = normalizeRequiredText(
                roleName,
                "INVALID_ROLE_NAME",
                "Role name is required."
        ).toUpperCase();

        if (!normalized.matches("^[A-Z][A-Z0-9_]{2,79}$")) {
            throw AuthException.badRequest(
                    "INVALID_ROLE_NAME",
                    "Role name must start with a letter and contain only letters, numbers, or underscores."
            );
        }

        return normalized;
    }

    private String normalizeRequiredPermissionCode(String permissionCode) {
        String normalized = normalizeRequiredText(
                permissionCode,
                "INVALID_PERMISSION_CODE",
                "Permission code is required."
        ).toLowerCase();

        if (!normalized.matches("^[a-z][a-z0-9_:.-]{2,99}$")) {
            throw AuthException.badRequest(
                    "INVALID_PERMISSION_CODE",
                    "Permission code is invalid."
            );
        }

        return normalized;
    }

    private String normalizeRequiredText(
            String value,
            String code,
            String message
    ) {
        if (!StringUtils.hasText(value)) {
            throw AuthException.badRequest(code, message);
        }

        return value.trim();
    }

    private void requireCustomRole(Role role, String action) {
        if (role.isSystemRole()) {
            throw AuthException.conflict(
                    "SYSTEM_ROLE_PROTECTED",
                    "System roles cannot be " + action + "."
            );
        }
    }

    private RoleResponse toResponse(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getName(),
                role.getDisplayName(),
                role.getDescription(),
                role.isSystemRole(),
                roleRepository.findPermissionCodesByRoleId(role.getId()),
                role.getCreatedAt(),
                role.getUpdatedAt()
        );
    }

    private PermissionResponse toResponse(Permission permission) {
        return new PermissionResponse(
                permission.getId(),
                permission.getCode(),
                permission.getCategory(),
                permission.getDescription()
        );
    }
}
