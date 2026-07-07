package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.dto.UserCreateRequest;
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
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class UserManagementServiceTests {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final DepartmentRepository departmentRepository =
            mock(DepartmentRepository.class);
    private final PasswordEncoder passwordEncoder =
            mock(PasswordEncoder.class);
    private final PermissionService permissionService =
            mock(PermissionService.class);

    private final UserManagementService userManagementService =
            new UserManagementService(
                    userRepository,
                    roleRepository,
                    departmentRepository,
                    passwordEncoder,
                    permissionService
            );

    @Test
    void createsUserWithDefaultEmployeeRole() {
        Role employee = role(3L, "EMPLOYEE");
        given(userRepository.existsByEmail("user@example.com"))
                .willReturn(false);
        given(roleRepository.findByName("EMPLOYEE"))
                .willReturn(Optional.of(employee));
        given(passwordEncoder.encode("Password123!"))
                .willReturn("bcrypt-hash");
        given(userRepository.saveAndFlush(any(User.class)))
                .willAnswer(invocation -> withPersistentFields(
                        invocation.getArgument(0),
                        42L
                ));

        UserResponse response = userManagementService.createUser(
                new UserCreateRequest(
                        " USER@Example.COM ",
                        "Password123!",
                        " Ada ",
                        " Lovelace ",
                        null,
                        null,
                        null,
                        null
                )
        );

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.firstName()).isEqualTo("Ada");
        assertThat(response.lastName()).isEqualTo("Lovelace");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.enabled()).isTrue();
        assertThat(response.primaryRole()).isEqualTo("EMPLOYEE");
        assertThat(response.roles()).containsExactly("EMPLOYEE");

        verify(userRepository).deleteRoleAssignments(42L);
        verify(userRepository).grantRole(42L, 3L, true);
    }

    @Test
    void rejectsDuplicateUserEmail() {
        given(userRepository.existsByEmail("user@example.com"))
                .willReturn(true);

        assertThatThrownBy(() -> userManagementService.createUser(
                new UserCreateRequest(
                        "user@example.com",
                        "Password123!",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("ACCOUNT_ALREADY_EXISTS")
                );

        verifyNoInteractions(roleRepository, passwordEncoder);
    }

    @Test
    void listsUsersWithBulkLoadedRoles() {
        User admin = user(1L, "admin@example.com", "COMPANY_ADMIN");
        User auditor = user(2L, "audit@example.com", "AUDITOR");
        given(userRepository.findAll(
                any(Specification.class),
                eq(PageRequest.of(0, 20))
        ))
                .willReturn(new PageImpl<>(List.of(admin, auditor)));
        given(userRepository.findRoleNamesByUserIds(Set.of(1L, 2L)))
                .willReturn(List.of(
                        roleName(1L, "COMPANY_ADMIN"),
                        roleName(1L, "AUDITOR"),
                        roleName(2L, "AUDITOR")
                ));

        Page<UserResponse> response = userManagementService.listUsers(
                UserStatus.ACTIVE,
                null,
                null,
                "example",
                PageRequest.of(0, 20)
        );

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).roles())
                .containsExactly("COMPANY_ADMIN", "AUDITOR");
        assertThat(response.getContent().get(1).roles())
                .containsExactly("AUDITOR");
    }

    @Test
    void updatesUserStatusAndDepartment() {
        User user = user(7L, "user@example.com", "EMPLOYEE");
        Department department = department(11L, "IT Support", true);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(departmentRepository.findById(11L))
                .willReturn(Optional.of(department));
        given(userRepository.findRoleNamesByUserIds(Set.of(7L)))
                .willReturn(List.of(roleName(7L, "EMPLOYEE")));

        UserResponse response = userManagementService.updateUser(
                7L,
                new UserUpdateRequest(
                        "Grace",
                        "Hopper",
                        11L,
                        null,
                        UserStatus.SUSPENDED
                )
        );

        assertThat(response.firstName()).isEqualTo("Grace");
        assertThat(response.lastName()).isEqualTo("Hopper");
        assertThat(response.departmentId()).isEqualTo(11L);
        assertThat(response.departmentName()).isEqualTo("IT Support");
        assertThat(response.status()).isEqualTo("SUSPENDED");
        assertThat(response.enabled()).isFalse();
    }

    @Test
    void rejectsInactiveDepartmentAssignment() {
        User user = user(7L, "user@example.com", "EMPLOYEE");
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(departmentRepository.findById(11L))
                .willReturn(Optional.of(department(11L, "Old IT", false)));

        assertThatThrownBy(() -> userManagementService.updateUser(
                7L,
                new UserUpdateRequest(null, null, 11L, null, null)
        ))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("DEPARTMENT_INACTIVE")
                );
    }

    @Test
    void assignsRolesAndUpdatesPrimaryRole() {
        User user = user(7L, "user@example.com", "EMPLOYEE");
        Role auditor = role(4L, "AUDITOR");
        Role helpdeskLead = role(9L, "HELPDESK_LEAD");
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(roleRepository.findAllById(Set.of(4L, 9L)))
                .willReturn(List.of(auditor, helpdeskLead));

        UserResponse response = userManagementService.assignRoles(
                7L,
                new UserRoleAssignmentRequest(Set.of(4L, 9L), 9L)
        );

        assertThat(response.primaryRole()).isEqualTo("HELPDESK_LEAD");
        assertThat(response.roles()).containsExactly("AUDITOR", "HELPDESK_LEAD");

        verify(userRepository).saveAndFlush(user);
        verify(userRepository).deleteRoleAssignments(7L);
        verify(userRepository).grantRole(7L, 4L, false);
        verify(userRepository).grantRole(7L, 9L, true);
    }

    @Test
    void rejectsPrimaryRoleThatIsNotAssigned() {
        User user = user(7L, "user@example.com", "EMPLOYEE");
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> userManagementService.assignRoles(
                7L,
                new UserRoleAssignmentRequest(Set.of(4L), 9L)
        ))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("PRIMARY_ROLE_NOT_ASSIGNED")
                );

        verifyNoInteractions(roleRepository);
    }

    @Test
    void disablesUserWithoutDeletingHistory() {
        User user = user(7L, "user@example.com", "EMPLOYEE");
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        userManagementService.disableUser(7L);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(user.isEnabled()).isFalse();
        verify(userRepository).findById(7L);
        verifyNoMoreInteractions(userRepository);
    }

    private User user(Long id, String email, String roleName) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("bcrypt-hash");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setRole(roleName);
        user.setStatus(UserStatus.ACTIVE);
        user.setEnabled(true);
        return withPersistentFields(user, id);
    }

    private User withPersistentFields(User user, Long id) {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        ReflectionTestUtils.setField(user, "id", id);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    private Role role(Long id, String name) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", id);
        role.setName(name);
        role.setDisplayName(name.replace('_', ' '));
        role.setDescription("Test role.");
        role.setSystemRole(name.equals("EMPLOYEE") || name.equals("AUDITOR"));
        role.setCreatedAt(Instant.parse("2026-07-07T00:00:00Z"));
        role.setUpdatedAt(Instant.parse("2026-07-07T00:00:00Z"));
        return role;
    }

    private Department department(Long id, String name, boolean active) {
        Department department = new Department();
        ReflectionTestUtils.setField(department, "id", id);
        department.setName(name);
        department.setCode(name.toLowerCase().replace(' ', '_'));
        department.setActive(active);
        department.setCreatedAt(Instant.parse("2026-07-07T00:00:00Z"));
        department.setUpdatedAt(Instant.parse("2026-07-07T00:00:00Z"));
        return department;
    }

    private UserRepository.UserRoleNameView roleName(
            Long userId,
            String roleName
    ) {
        return new UserRepository.UserRoleNameView() {
            @Override
            public Long getUserId() {
                return userId;
            }

            @Override
            public String getRoleName() {
                return roleName;
            }
        };
    }
}
