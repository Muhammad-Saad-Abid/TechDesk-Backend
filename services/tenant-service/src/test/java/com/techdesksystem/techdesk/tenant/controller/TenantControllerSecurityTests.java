package com.techdesksystem.techdesk.tenant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techdesksystem.techdesk.tenant.config.SecurityConfig;
import com.techdesksystem.techdesk.tenant.config.TenantSecurityProperties;
import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.dto.TenantResponse;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import com.techdesksystem.techdesk.tenant.service.TenantManagementService;
import com.techdesksystem.techdesk.tenant.service.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

@WebMvcTest(TenantController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(TenantSecurityProperties.class)
@TestPropertySource(properties =
        "tenant.security.jwt-secret=test-only-secret-key-that-is-at-least-32-bytes-long")
class TenantControllerSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantProvisioningService provisioningService;

    @MockitoBean
    private TenantManagementService managementService;

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void nonSuperAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/tenants")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_EMPLOYEE")
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void superAdminCanCreateTenantWithoutSupplyingAdminPassword() throws Exception {
        TenantResponse response = response();
        given(provisioningService.provision(any(CreateTenantRequest.class)))
                .willReturn(response);

        mockMvc.perform(post("/api/tenants")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Acme Pakistan",
                                  "slug": "acme-pakistan",
                                  "adminEmail": "admin@acme.example",
                                  "adminFirstName": "Ayesha",
                                  "adminLastName": "Khan"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaName")
                        .value("tenant_acme_pakistan"))
                .andExpect(jsonPath("$.provisioningStatus").value("READY"));
    }

    @Test
    void invalidCreateRequestReturnsStructuredFieldErrors() throws Exception {
        mockMvc.perform(post("/api/tenants")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "slug": "acme",
                                  "adminEmail": "not-an-email",
                                  "adminFirstName": "",
                                  "adminLastName": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.adminEmail").exists());
    }

    private TenantResponse response() {
        Instant now = Instant.now();
        return new TenantResponse(
                UUID.randomUUID(),
                "Acme Pakistan",
                "acme-pakistan",
                "tenant_acme_pakistan",
                TenantStatus.ACTIVE,
                ProvisioningStatus.READY,
                now,
                now
        );
    }
}
