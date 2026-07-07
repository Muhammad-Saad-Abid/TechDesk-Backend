package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.config.SecurityConfig;
import com.techdesksystem.techdesk.auth.dto.PermissionResponse;
import com.techdesksystem.techdesk.auth.dto.RoleCreateRequest;
import com.techdesksystem.techdesk.auth.dto.RolePermissionsUpdateRequest;
import com.techdesksystem.techdesk.auth.dto.RoleResponse;
import com.techdesksystem.techdesk.auth.security.PermissionService;
import com.techdesksystem.techdesk.auth.security.RequiresPermissionAspect;
import com.techdesksystem.techdesk.auth.service.RoleManagementService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoleManagementController.class)
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
class RoleManagementControllerTests {

    private static final Instant NOW =
            Instant.parse("2026-07-07T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleManagementService roleManagementService;

    @MockitoBean
    private PermissionService permissionService;

    @Test
    void createsRoleWhenCallerHasPermission() throws Exception {
        given(permissionService.currentUserHasPermission("roles:create"))
                .willReturn(true);
        given(roleManagementService.createRole(any(RoleCreateRequest.class)))
                .willReturn(roleResponse(
                        11L,
                        "HELPDESK_LEAD",
                        false,
                        List.of("tickets:assign")
                ));

        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "HELPDESK_LEAD",
                                  "displayName": "Helpdesk Lead",
                                  "description": "Coordinates support tickets.",
                                  "permissionCodes": ["tickets:assign"]
                                }
                                """)
                        .with(accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.name").value("HELPDESK_LEAD"))
                .andExpect(jsonPath("$.permissions[0]")
                        .value("tickets:assign"));

        verify(roleManagementService).createRole(any(RoleCreateRequest.class));
    }

    @Test
    void rejectsRoleCreateWhenCallerLacksPermission() throws Exception {
        given(permissionService.currentUserHasPermission("roles:create"))
                .willReturn(false);

        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "HELPDESK_LEAD",
                                  "displayName": "Helpdesk Lead",
                                  "description": "Coordinates support tickets."
                                }
                                """)
                        .with(accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(roleManagementService);
    }

    @Test
    void listsPermissionsWhenCallerHasRoleReadPermission()
            throws Exception {
        given(permissionService.currentUserHasPermission("roles:read"))
                .willReturn(true);
        given(roleManagementService.listPermissions(
                eq("tickets"),
                eq("assign"),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(
                new PermissionResponse(
                        1L,
                        "tickets:assign",
                        "tickets",
                        "Assign tickets."
                )
        )));

        mockMvc.perform(get("/api/permissions")
                        .param("category", "tickets")
                        .param("q", "assign")
                        .with(accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code")
                        .value("tickets:assign"));

        verify(roleManagementService).listPermissions(
                eq("tickets"),
                eq("assign"),
                any(Pageable.class)
        );
    }

    @Test
    void updatesRolePermissionsWhenCallerHasAssignPermission()
            throws Exception {
        given(permissionService.currentUserHasPermission("roles:assign"))
                .willReturn(true);
        given(roleManagementService.updateRolePermissions(
                eq(11L),
                any(RolePermissionsUpdateRequest.class)
        )).willReturn(roleResponse(
                11L,
                "HELPDESK_LEAD",
                false,
                List.of("tickets:assign")
        ));

        mockMvc.perform(put("/api/roles/11/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "permissionCodes": ["tickets:assign"]
                                }
                                """)
                        .with(accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]")
                        .value("tickets:assign"));

        verify(roleManagementService).updateRolePermissions(
                eq(11L),
                any(RolePermissionsUpdateRequest.class)
        );
    }

    @Test
    void deletesRoleWhenCallerHasDeletePermission() throws Exception {
        given(permissionService.currentUserHasPermission("roles:delete"))
                .willReturn(true);

        mockMvc.perform(delete("/api/roles/11")
                        .with(accessToken()))
                .andExpect(status().isNoContent());

        verify(roleManagementService).deleteRole(11L);
    }

    private RoleResponse roleResponse(
            Long id,
            String name,
            boolean systemRole,
            List<String> permissions
    ) {
        return new RoleResponse(
                id,
                name,
                "Helpdesk Lead",
                "Coordinates support tickets.",
                systemRole,
                permissions,
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
