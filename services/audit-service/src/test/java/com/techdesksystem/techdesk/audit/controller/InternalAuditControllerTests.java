package com.techdesksystem.techdesk.audit.controller;

import com.techdesksystem.techdesk.audit.config.AuditSecurityProperties;
import com.techdesksystem.techdesk.audit.service.TenantIsolationAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalAuditController.class)
@Import(AuditSecurityProperties.class)
@TestPropertySource(properties = "audit.internal-key=0123456789abcdef0123456789abcdef")
class InternalAuditControllerTests {

    private static final String INTERNAL_KEY = "0123456789abcdef0123456789abcdef";

    private static final String BODY = """
            {
              "service": "auth-service",
              "expectedTenant": "tenant_alpha",
              "activeSchema": "tenant_bravo",
              "reason": "Schema mismatch",
              "sqlFingerprint": "abcdef0123456789",
              "occurredAt": "2026-07-05T00:00:00Z"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TenantIsolationAuditService auditService;

    @Test
    void validInternalCredentialStoresViolation() throws Exception {
        mockMvc.perform(post("/internal/audit/tenant-isolation-violations")
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        verify(auditService).record(argThat(request ->
                "tenant_alpha".equals(request.expectedTenant())
                        && "tenant_bravo".equals(request.activeSchema())
        ));
    }

    @Test
    void invalidInternalCredentialIsRejected() throws Exception {
        mockMvc.perform(post("/internal/audit/tenant-isolation-violations")
                        .header("X-Internal-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(auditService);
    }
}
