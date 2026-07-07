package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.config.SecurityConfig;
import com.techdesksystem.techdesk.auth.dto.DepartmentCreateRequest;
import com.techdesksystem.techdesk.auth.dto.DepartmentResponse;
import com.techdesksystem.techdesk.auth.security.PermissionService;
import com.techdesksystem.techdesk.auth.security.RequiresPermissionAspect;
import com.techdesksystem.techdesk.auth.service.DepartmentService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DepartmentController.class)
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
class DepartmentControllerTests {

    private static final Instant NOW =
            Instant.parse("2026-07-07T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepartmentService departmentService;

    @MockitoBean
    private PermissionService permissionService;

    @Test
    void createsDepartmentWhenCallerHasPermission() throws Exception {
        given(permissionService.currentUserHasPermission(
                "departments:create"
        )).willReturn(true);
        given(departmentService.createDepartment(any(
                DepartmentCreateRequest.class
        ))).willReturn(response(1L, "Engineering", "engineering", true));

        mockMvc.perform(post("/api/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Engineering",
                                  "code": "engineering"
                                }
                                """)
                        .with(jwt().jwt(token -> token
                                .claim("userId", 7L)
                                .claim("tenantId", "tenant_alpha")
                                .claim("tokenType", "access"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Engineering"))
                .andExpect(jsonPath("$.code").value("engineering"))
                .andExpect(jsonPath("$.active").value(true));

        verify(departmentService).createDepartment(any(
                DepartmentCreateRequest.class
        ));
    }

    @Test
    void rejectsDepartmentCreateWhenCallerLacksPermission()
            throws Exception {
        given(permissionService.currentUserHasPermission(
                "departments:create"
        )).willReturn(false);

        mockMvc.perform(post("/api/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Engineering",
                                  "code": "engineering"
                                }
                                """)
                        .with(jwt().jwt(token -> token
                                .claim("userId", 7L)
                                .claim("tenantId", "tenant_alpha")
                                .claim("tokenType", "access"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(departmentService);
    }

    @Test
    void listsDepartmentsWithFiltersWhenCallerHasPermission()
            throws Exception {
        given(permissionService.currentUserHasPermission("departments:read"))
                .willReturn(true);
        given(departmentService.listDepartments(
                eq(true),
                eq("eng"),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(
                response(1L, "Engineering", "engineering", true)
        )));

        mockMvc.perform(get("/api/departments")
                        .param("active", "true")
                        .param("q", "eng")
                        .with(jwt().jwt(token -> token
                                .claim("userId", 7L)
                                .claim("tenantId", "tenant_alpha")
                                .claim("tokenType", "access"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name")
                        .value("Engineering"));

        verify(departmentService).listDepartments(
                eq(true),
                eq("eng"),
                any(Pageable.class)
        );
    }

    @Test
    void deletesDepartmentWhenCallerHasPermission() throws Exception {
        given(permissionService.currentUserHasPermission(
                "departments:delete"
        )).willReturn(true);

        mockMvc.perform(delete("/api/departments/9")
                        .with(jwt().jwt(token -> token
                                .claim("userId", 7L)
                                .claim("tenantId", "tenant_alpha")
                                .claim("tokenType", "access"))))
                .andExpect(status().isNoContent());

        verify(departmentService).deactivateDepartment(9L);
    }

    private DepartmentResponse response(
            Long id,
            String name,
            String code,
            boolean active
    ) {
        return new DepartmentResponse(id, name, code, active, NOW, NOW);
    }
}
