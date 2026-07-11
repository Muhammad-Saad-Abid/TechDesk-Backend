package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.dto.UserCreateRequest;
import com.techdesksystem.techdesk.auth.dto.UserInvitationRequest;
import com.techdesksystem.techdesk.auth.dto.UserPermissionsResponse;
import com.techdesksystem.techdesk.auth.dto.UserResponse;
import com.techdesksystem.techdesk.auth.dto.UserRoleAssignmentRequest;
import com.techdesksystem.techdesk.auth.dto.UserUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.Department;
import com.techdesksystem.techdesk.auth.entity.Role;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.entity.UserStatus;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.DepartmentRepository;
import com.techdesksystem.techdesk.auth.repository.RoleRepository;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import com.techdesksystem.techdesk.auth.security.PermissionService;
import com.techdesksystem.techdesk.auth.tenant.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserManagementService {

    private static final String DEFAULT_ROLE_NAME = "EMPLOYEE";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final PasswordResetService passwordResetService;

    public UserManagementService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            DepartmentRepository departmentRepository,
            PasswordEncoder passwordEncoder,
            PermissionService permissionService,
            PasswordResetService passwordResetService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
        this.passwordResetService = passwordResetService;
    }

    /**
     * Creates an active tenant-local user from the admin user-management screen.
     * Invitation-based onboarding can sit beside this later using the same
     * status and role-assignment foundation.
     */
    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw duplicateEmail();
        }

        ResolvedRoles resolvedRoles = resolveCreateRoles(request);

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(trimToNull(request.firstName()));
        user.setLastName(trimToNull(request.lastName()));
        user.setDepartment(resolveDepartment(request.departmentId()));
        user.setRole(resolvedRoles.primaryRole().getName());
        applyStatus(user, request.status() == null
                ? UserStatus.ACTIVE
                : request.status());

        User savedUser;
        try {
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateEmail();
        }

        replaceRoleAssignments(
                savedUser.getId(),
                resolvedRoles.roles(),
                resolvedRoles.primaryRole().getId()
        );

        return toResponse(
                savedUser,
                resolvedRoles.roles().stream()
                        .map(Role::getName)
                        .sorted()
                        .toList()
        );
    }

    /**
     * Creates an INVITED user and emails a single-use onboarding link so the
     * user can set their own password before the account becomes active.
     */
    @Transactional
    public UserResponse inviteUser(UserInvitationRequest request) {
        String tenantId = TenantContext.requireTenant();
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw duplicateEmail();
        }

        ResolvedRoles resolvedRoles = resolveInvitationRoles(request);

        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(
                UUID.randomUUID() + ":" + UUID.randomUUID()
        ));
        user.setFirstName(trimToNull(request.firstName()));
        user.setLastName(trimToNull(request.lastName()));
        user.setDepartment(resolveDepartment(request.departmentId()));
        user.setRole(resolvedRoles.primaryRole().getName());
        applyStatus(user, UserStatus.INVITED);

        User savedUser;
        try {
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateEmail();
        }

        replaceRoleAssignments(
                savedUser.getId(),
                resolvedRoles.roles(),
                resolvedRoles.primaryRole().getId()
        );
        passwordResetService.sendInvitation(savedUser);

        return toResponse(
                savedUser,
                resolvedRoles.roles().stream()
                        .map(Role::getName)
                        .sorted()
                        .toList()
        );
    }

    /**
     * Lists tenant users with lightweight filters for admin grids.
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(
            UserStatus status,
            String primaryRole,
            Long departmentId,
            String search,
            Pageable pageable
    ) {
        Page<User> users = userRepository.findAll(
                userFilters(status, primaryRole, departmentId, search),
                pageable
        );
        Map<Long, List<String>> rolesByUserId =
                loadRoleNamesByUserId(users.getContent());

        return users.map(user -> toResponse(
                user,
                rolesByUserId.getOrDefault(user.getId(), List.of(user.getRole()))
        ));
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(Long userId) {
        User user = findUser(userId);
        return toResponse(
                user,
                loadRoleNamesByUserId(List.of(user))
                        .getOrDefault(userId, List.of(user.getRole()))
        );
    }

    /**
     * Updates mutable user profile/lifecycle fields. Role assignment is kept as
     * a separate endpoint because it has different permission semantics.
     */
    @Transactional
    public UserResponse updateUser(Long userId, UserUpdateRequest request) {
        if (!hasAnyUserUpdate(request)) {
            throw AuthException.badRequest(
                    "NO_USER_UPDATES",
                    "At least one user field must be provided."
            );
        }

        if (Boolean.TRUE.equals(request.clearDepartment())
                && request.departmentId() != null) {
            throw AuthException.badRequest(
                    "AMBIGUOUS_DEPARTMENT_UPDATE",
                    "Provide either departmentId or clearDepartment, not both."
            );
        }

        User user = findUser(userId);

        if (request.firstName() != null) {
            user.setFirstName(trimToNull(request.firstName()));
        }
        if (request.lastName() != null) {
            user.setLastName(trimToNull(request.lastName()));
        }
        if (request.departmentId() != null) {
            user.setDepartment(resolveDepartment(request.departmentId()));
        }
        if (Boolean.TRUE.equals(request.clearDepartment())) {
            user.setDepartment(null);
        }
        if (request.status() != null) {
            applyStatus(user, request.status());
        }

        return toResponse(
                user,
                loadRoleNamesByUserId(List.of(user))
                        .getOrDefault(userId, List.of(user.getRole()))
        );
    }

    /**
     * Replaces all assigned roles for a user and keeps auth_users.role in sync
     * with the selected primary role for legacy JWT/client compatibility.
     */
    @Transactional
    public UserResponse assignRoles(
            Long userId,
            UserRoleAssignmentRequest request
    ) {
        User user = findUser(userId);
        ResolvedRoles resolvedRoles =
                resolveRoles(request.roleIds(), request.primaryRoleId());

        user.setRole(resolvedRoles.primaryRole().getName());
        userRepository.saveAndFlush(user);

        replaceRoleAssignments(
                userId,
                resolvedRoles.roles(),
                request.primaryRoleId()
        );

        return toResponse(
                user,
                resolvedRoles.roles().stream()
                        .map(Role::getName)
                        .sorted()
                        .toList()
        );
    }

    /**
     * Soft-deletes users by disabling login while preserving audit history and
     * historical ticket ownership.
     */
    @Transactional
    public void disableUser(Long userId) {
        User user = findUser(userId);
        applyStatus(user, UserStatus.DISABLED);
    }

    /**
     * Returns the flattened effective permission list for a tenant-local user.
     */
    @Transactional(readOnly = true)
    public UserPermissionsResponse permissionsForUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw AuthException.notFound(
                    "USER_NOT_FOUND",
                    "User was not found."
            );
        }

        List<String> permissions =
                permissionService.effectivePermissionsForUser(userId);
        return new UserPermissionsResponse(userId, permissions);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> AuthException.notFound(
                        "USER_NOT_FOUND",
                        "User was not found."
                ));
    }

    private ResolvedRoles resolveCreateRoles(UserCreateRequest request) {
        if (request.roleIds() == null || request.roleIds().isEmpty()) {
            Role defaultRole = roleRepository.findByName(DEFAULT_ROLE_NAME)
                    .orElseThrow(() -> AuthException.badRequest(
                            "DEFAULT_ROLE_NOT_FOUND",
                            "Default employee role is not available."
                    ));
            return new ResolvedRoles(List.of(defaultRole), defaultRole);
        }

        return resolveRoles(request.roleIds(), request.primaryRoleId());
    }

    private ResolvedRoles resolveInvitationRoles(UserInvitationRequest request) {
        if (request.roleIds() == null || request.roleIds().isEmpty()) {
            Role defaultRole = roleRepository.findByName(DEFAULT_ROLE_NAME)
                    .orElseThrow(() -> AuthException.badRequest(
                            "DEFAULT_ROLE_NOT_FOUND",
                            "Default employee role is not available."
                    ));
            return new ResolvedRoles(List.of(defaultRole), defaultRole);
        }

        return resolveRoles(request.roleIds(), request.primaryRoleId());
    }

    private ResolvedRoles resolveRoles(
            Set<Long> roleIds,
            Long primaryRoleId
    ) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw AuthException.badRequest(
                    "NO_ROLES_PROVIDED",
                    "At least one role is required."
            );
        }
        if (primaryRoleId == null) {
            throw AuthException.badRequest(
                    "PRIMARY_ROLE_REQUIRED",
                    "Primary role id is required."
            );
        }

        Set<Long> normalizedRoleIds = roleIds.stream()
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!normalizedRoleIds.contains(primaryRoleId)) {
            throw AuthException.badRequest(
                    "PRIMARY_ROLE_NOT_ASSIGNED",
                    "Primary role must be included in roleIds."
            );
        }

        Map<Long, Role> rolesById = roleRepository.findAllById(normalizedRoleIds)
                .stream()
                .collect(Collectors.toMap(
                        Role::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<Long> missingRoleIds = normalizedRoleIds.stream()
                .filter(roleId -> !rolesById.containsKey(roleId))
                .toList();
        if (!missingRoleIds.isEmpty()) {
            throw AuthException.badRequest(
                    "UNKNOWN_ROLE_ID",
                    "Unknown role id: " + missingRoleIds.get(0)
            );
        }

        Role primaryRole = rolesById.get(primaryRoleId);
        List<Role> sortedRoles = rolesById.values()
                .stream()
                .sorted(Comparator.comparing(Role::getName))
                .toList();

        return new ResolvedRoles(sortedRoles, primaryRole);
    }

    private void replaceRoleAssignments(
            Long userId,
            Collection<Role> roles,
            Long primaryRoleId
    ) {
        userRepository.deleteRoleAssignments(userId);
        roles.forEach(role -> userRepository.grantRole(
                userId,
                role.getId(),
                role.getId().equals(primaryRoleId)
        ));
    }

    private Department resolveDepartment(Long departmentId) {
        if (departmentId == null) {
            return null;
        }

        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> AuthException.notFound(
                        "DEPARTMENT_NOT_FOUND",
                        "Department was not found."
                ));

        if (!department.isActive()) {
            throw AuthException.badRequest(
                    "DEPARTMENT_INACTIVE",
                    "Department is inactive."
            );
        }

        return department;
    }

    private void applyStatus(User user, UserStatus status) {
        user.setStatus(status);
        user.setEnabled(status == UserStatus.ACTIVE);
    }

    private Specification<User> userFilters(
            UserStatus status,
            String primaryRole,
            Long departmentId,
            String search
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("status"),
                        status
                ));
            }

            if (StringUtils.hasText(primaryRole)) {
                predicates.add(criteriaBuilder.equal(
                        criteriaBuilder.upper(root.get("role")),
                        primaryRole.trim().toUpperCase(Locale.ROOT)
                ));
            }

            if (departmentId != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("department").get("id"),
                        departmentId
                ));
            }

            if (StringUtils.hasText(search)) {
                String pattern = "%"
                        + search.trim().toLowerCase(Locale.ROOT)
                        + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("email")),
                                pattern
                        ),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("firstName")),
                                pattern
                        ),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("lastName")),
                                pattern
                        )
                ));
            }

            return criteriaBuilder.and(
                    predicates.toArray(Predicate[]::new)
            );
        };
    }

    private Map<Long, List<String>> loadRoleNamesByUserId(List<User> users) {
        if (users.isEmpty()) {
            return Map.of();
        }

        Set<Long> userIds = users.stream()
                .map(User::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, List<String>> rolesByUserId = new LinkedHashMap<>();
        userRepository.findRoleNamesByUserIds(userIds)
                .forEach(roleNameView ->
                        rolesByUserId
                                .computeIfAbsent(
                                        roleNameView.getUserId(),
                                        ignored -> new ArrayList<>()
                                )
                                .add(roleNameView.getRoleName())
                );

        return rolesByUserId;
    }

    private UserResponse toResponse(User user, List<String> roles) {
        Department department = user.getDepartment();
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus().name(),
                user.isEnabled(),
                department == null ? null : department.getId(),
                department == null ? null : department.getName(),
                user.getRole(),
                roles,
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }

    private boolean hasAnyUserUpdate(UserUpdateRequest request) {
        return request.firstName() != null
                || request.lastName() != null
                || request.departmentId() != null
                || request.clearDepartment() != null
                || request.status() != null;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private AuthException duplicateEmail() {
        return AuthException.conflict(
                "ACCOUNT_ALREADY_EXISTS",
                "An account with this email already exists for this tenant."
        );
    }

    private record ResolvedRoles(
            List<Role> roles,
            Role primaryRole
    ) {
    }
}
