package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.dto.RoleCreateRequest;
import com.techdesksystem.techdesk.auth.dto.RolePermissionsUpdateRequest;
import com.techdesksystem.techdesk.auth.dto.RoleResponse;
import com.techdesksystem.techdesk.auth.dto.RoleUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.Permission;
import com.techdesksystem.techdesk.auth.entity.Role;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.PermissionRepository;
import com.techdesksystem.techdesk.auth.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RoleManagementServiceTests {

    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final PermissionRepository permissionRepository =
            mock(PermissionRepository.class);

    private final RoleManagementService roleManagementService =
            new RoleManagementService(roleRepository, permissionRepository);

    @Test
    void createsCustomRoleWithValidatedPermissionAssignments() {
        given(roleRepository.existsByName("HELPDESK_LEAD"))
                .willReturn(false);
        given(permissionRepository.findByCodeIn(Set.of(
                "tickets:read:all",
                "tickets:assign"
        ))).willReturn(List.of(
                permission(1L, "tickets:read:all"),
                permission(2L, "tickets:assign")
        ));
        given(roleRepository.save(any(Role.class)))
                .willAnswer(invocation -> withPersistentFields(
                        invocation.getArgument(0),
                        20L
                ));
        given(roleRepository.findPermissionCodesByRoleId(20L))
                .willReturn(List.of(
                        "tickets:assign",
                        "tickets:read:all"
                ));

        RoleResponse response = roleManagementService.createRole(
                new RoleCreateRequest(
                        "helpdesk_lead",
                        " Helpdesk Lead ",
                        "Can coordinate support tickets.",
                        Set.of("tickets:read:all", "tickets:assign")
                )
        );

        assertThat(response.id()).isEqualTo(20L);
        assertThat(response.name()).isEqualTo("HELPDESK_LEAD");
        assertThat(response.displayName()).isEqualTo("Helpdesk Lead");
        assertThat(response.systemRole()).isFalse();
        assertThat(response.permissions())
                .containsExactly("tickets:assign", "tickets:read:all");

        verify(roleRepository).deletePermissionAssignments(20L);
        verify(roleRepository).grantPermission(20L, "tickets:read:all");
        verify(roleRepository).grantPermission(20L, "tickets:assign");
    }

    @Test
    void rejectsUnknownPermissionCodes() {
        given(roleRepository.existsByName("HELPDESK_LEAD"))
                .willReturn(false);
        given(permissionRepository.findByCodeIn(Set.of(
                "tickets:read:all",
                "roles:nuclear"
        ))).willReturn(List.of(permission(1L, "tickets:read:all")));

        assertThatThrownBy(() -> roleManagementService.createRole(
                new RoleCreateRequest(
                        "HELPDESK_LEAD",
                        "Helpdesk Lead",
                        "Can coordinate support tickets.",
                        Set.of("tickets:read:all", "roles:nuclear")
                )
        ))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("roles:nuclear");
    }

    @Test
    void protectsSystemRolesFromUpdates() {
        Role systemRole = role(1L, "COMPANY_ADMIN", true);
        given(roleRepository.findById(1L))
                .willReturn(Optional.of(systemRole));

        assertThatThrownBy(() -> roleManagementService.updateRole(
                1L,
                new RoleUpdateRequest("Admin", null, null)
        ))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("System roles cannot be updated");
    }

    @Test
    void protectsAssignedCustomRolesFromDeletion() {
        Role customRole = role(5L, "HELPDESK_LEAD", false);
        given(roleRepository.findById(5L))
                .willReturn(Optional.of(customRole));
        given(roleRepository.countUserAssignments(5L))
                .willReturn(2L);

        assertThatThrownBy(() -> roleManagementService.deleteRole(5L))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("users are assigned");

        verify(roleRepository).findById(5L);
        verify(roleRepository).countUserAssignments(5L);
        verifyNoMoreInteractions(roleRepository);
    }

    @Test
    void replacesPermissionsForCustomRole() {
        Role customRole = role(9L, "HELPDESK_LEAD", false);
        given(roleRepository.findById(9L))
                .willReturn(Optional.of(customRole));
        given(permissionRepository.findByCodeIn(Set.of("tickets:assign")))
                .willReturn(List.of(permission(2L, "tickets:assign")));
        given(roleRepository.findPermissionCodesByRoleId(9L))
                .willReturn(List.of("tickets:assign"));

        RoleResponse response = roleManagementService.updateRolePermissions(
                9L,
                new RolePermissionsUpdateRequest(Set.of("tickets:assign"))
        );

        assertThat(response.permissions()).containsExactly("tickets:assign");
        verify(roleRepository).deletePermissionAssignments(9L);
        verify(roleRepository).grantPermission(9L, "tickets:assign");
    }

    private Role role(Long id, String name, boolean systemRole) {
        Role role = new Role();
        role.setName(name);
        role.setDisplayName(name.replace('_', ' '));
        role.setDescription("Test role.");
        role.setSystemRole(systemRole);
        return withPersistentFields(role, id);
    }

    private Role withPersistentFields(Role role, Long id) {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        ReflectionTestUtils.setField(role, "id", id);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        return role;
    }

    private Permission permission(Long id, String code) {
        Permission permission = new Permission();
        ReflectionTestUtils.setField(permission, "id", id);
        permission.setCode(code);
        permission.setCategory(code.substring(0, code.indexOf(':')));
        permission.setDescription("Test permission.");
        permission.setCreatedAt(Instant.parse("2026-07-07T00:00:00Z"));
        return permission;
    }
}
