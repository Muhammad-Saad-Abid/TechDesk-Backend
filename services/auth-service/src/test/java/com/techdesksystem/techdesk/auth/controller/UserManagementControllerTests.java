package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.config.SecurityConfig;
import com.techdesksystem.techdesk.auth.dto.UserCreateRequest;
import com.techdesksystem.techdesk.auth.dto.UserPermissionsResponse;
import com.techdesksystem.techdesk.auth.dto.UserResponse;
import com.techdesksystem.techdesk.auth.dto.UserRoleAssignmentRequest;
import com.techdesksystem.techdesk.auth.dto.UserUpdateRequest;
import com.techdesksystem.techdesk.auth.entity.UserStatus;
import com.techdesksystem.techdesk.auth.security.PermissionService;
import com.techdesksystem.techdesk.auth.security.RequiresPermissionAspect;
import com.techdesksystem.techdesk.auth.service.UserManagementService;
import com.techdesksystem.techdesk.auth.tenant.TenantIdentifierValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserManagementController.class)
@Import({
        SecurityConfig.class,
        TenantIdentifierValidator.class,
        RequiresPermissionAspect.class
})
@EnableConfigurationProperties({
        AuthMultitenancyProperties.class,
        JwtProperties.class
})
@TestPropertySource(properties = {
        "auth.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long",
        "auth.multitenancy.enabled=true"
})
class UserManagementControllerTests {

    private static final Instant NOW =
            Instant.parse("2026-07-07T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserManagementService userManagementService;

    @MockitoBean
    private PermissionService permissionService;

    @Test
    void createsUserWhenCallerHasPermission() throws Exception {
        given(permissionService.currentUserHasPermission("users:create"))
                .willReturn(true);
        given(userManagementService.createUser(any(UserCreateRequest.class)))
                .willReturn(userResponse(
                        42L,
                        "user@example.com",
                        "EMPLOYEE",
                        List.of("EMPLOYEE")
                ));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "Password123!",
                                  "firstName": "Ada",
                                  "lastName": "Lovelace"
                                }
                                """)
                        .with(accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.primaryRole").value("EMPLOYEE"));

        verify(userManagementService).createUser(any(UserCreateRequest.class));
    }

    @Test
    void listsUsersWhenCallerHasReadPermission() throws Exception {
        given(permissionService.currentUserHasPermission("users:read"))
                .willReturn(true);
        given(userManagementService.listUsers(
                eq(UserStatus.ACTIVE),
                eq("EMPLOYEE"),
                eq(3L),
                eq("ada"),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(userResponse(
                42L,
                "user@example.com",
                "EMPLOYEE",
                List.of("EMPLOYEE")
        ))));

        mockMvc.perform(get("/api/users")
                        .param("status", "ACTIVE")
                        .param("primaryRole", "EMPLOYEE")
                        .param("departmentId", "3")
                        .param("q", "ada")
                        .with(accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(42))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));

        verify(userManagementService).listUsers(
                eq(UserStatus.ACTIVE),
                eq("EMPLOYEE"),
                eq(3L),
                eq("ada"),
                any(Pageable.class)
        );
    }

    @Test
    void updatesUserWhenCallerHasUpdatePermission() throws Exception {
        given(permissionService.currentUserHasPermission("users:update"))
                .willReturn(true);
        given(userManagementService.updateUser(
                eq(42L),
                any(UserUpdateRequest.class)
        )).willReturn(userResponse(
                42L,
                "user@example.com",
                "EMPLOYEE",
                List.of("EMPLOYEE")
        ));

        mockMvc.perform(patch("/api/users/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Ada",
                                  "status": "ACTIVE"
                                }
                                """)
                        .with(accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));

        verify(userManagementService).updateUser(
                eq(42L),
                any(UserUpdateRequest.class)
        );
    }

    @Test
    void assignsRolesWhenCallerHasAssignPermission() throws Exception {
        given(permissionService.currentUserHasPermission("roles:assign"))
                .willReturn(true);
        given(userManagementService.assignRoles(
                eq(42L),
                any(UserRoleAssignmentRequest.class)
        )).willReturn(userResponse(
                42L,
                "user@example.com",
                "HELPDESK_LEAD",
                List.of("AUDITOR", "HELPDESK_LEAD")
        ));

        mockMvc.perform(put("/api/users/42/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleIds": [4, 9],
                                  "primaryRoleId": 9
                                }
                                """)
                        .with(accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryRole").value("HELPDESK_LEAD"))
                .andExpect(jsonPath("$.roles[0]").value("AUDITOR"));

        verify(userManagementService).assignRoles(
                eq(42L),
                any(UserRoleAssignmentRequest.class)
        );
    }

    @Test
    void disablesUserWhenCallerHasDeletePermission() throws Exception {
        given(permissionService.currentUserHasPermission("users:delete"))
                .willReturn(true);

        mockMvc.perform(delete("/api/users/42")
                        .with(accessToken()))
                .andExpect(status().isNoContent());

        verify(userManagementService).disableUser(42L);
    }

    @Test
    void returnsFlattenedPermissionsWhenCallerHasPermission()
            throws Exception {
        given(permissionService.currentUserHasPermission(
                "users:permissions:read"
        )).willReturn(true);
        given(userManagementService.permissionsForUser(42L))
                .willReturn(new UserPermissionsResponse(
                        42L,
                        List.of("users:read", "users:update")
                ));

        mockMvc.perform(get("/api/users/42/permissions")
                        .with(accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.permissions[0]").value("users:read"))
                .andExpect(jsonPath("$.permissions[1]").value("users:update"));

        verify(userManagementService).permissionsForUser(42L);
    }

    @Test
    void rejectsWhenCallerLacksPermission() throws Exception {
        given(permissionService.currentUserHasPermission(
                "users:permissions:read"
        )).willReturn(false);

        mockMvc.perform(get("/api/users/42/permissions")
                        .with(accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(userManagementService);
    }

    private UserResponse userResponse(
            Long id,
            String email,
            String primaryRole,
            List<String> roles
    ) {
        return new UserResponse(
                id,
                email,
                "Ada",
                "Lovelace",
                "ACTIVE",
                true,
                null,
                null,
                primaryRole,
                roles,
                NOW,
                NOW
        );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    accessToken() {
        return jwt().jwt(token -> token
                .claim("userId", 7L)
                .claim("tenantId", "tenant_alpha")
                .claim("tokenType", "access"));
    }
}
