package com.techdesksystem.techdesk.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.techdesksystem.techdesk.auth.config.SecurityConfig;
import com.techdesksystem.techdesk.auth.config.AuthMultitenancyProperties;
import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.dto.AuthResponse;
import com.techdesksystem.techdesk.auth.dto.LogoutRequest;
import com.techdesksystem.techdesk.auth.dto.ForgotPasswordRequest;
import com.techdesksystem.techdesk.auth.dto.ResetPasswordRequest;
import com.techdesksystem.techdesk.auth.dto.RefreshTokenRequest;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.service.AuthService;
import com.techdesksystem.techdesk.auth.tenant.TenantIdentifierValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, TenantIdentifierValidator.class})
@EnableConfigurationProperties({
        AuthMultitenancyProperties.class,
        JwtProperties.class
})
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void refreshTokenUsesRoadmapEndpoint() throws Exception {
        given(authService.refresh(
                eq("tenant_example"),
                any(RefreshTokenRequest.class)
        )).willReturn(authResponse());

        mockMvc.perform(post("/api/auth/refresh-token")
                        .header("X-Tenant-ID", "tenant_example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void invalidLoginRequestReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Tenant-ID", "tenant_example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void replayedRefreshTokenReturnsUnauthorizedError() throws Exception {
        given(authService.refresh(
                eq("tenant_example"),
                any(RefreshTokenRequest.class)
        )).willThrow(AuthException.unauthorized(
                "REFRESH_TOKEN_REUSE_DETECTED",
                "Refresh token reuse detected. All active sessions were revoked."
        ));

        mockMvc.perform(post("/api/auth/refresh-token")
                        .header("X-Tenant-ID", "tenant_example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"replayed-refresh-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("REFRESH_TOKEN_REUSE_DETECTED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void logoutReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("X-Tenant-ID", "tenant_example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"active-refresh-token\"}"))
                .andExpect(status().isNoContent());

        verify(authService).logout(
                eq("tenant_example"),
                any(LogoutRequest.class)
        );
    }

    @Test
    void forgotPasswordAlwaysReturnsGenericAcceptedResponse() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .header("X-Tenant-ID", "tenant_example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "If an eligible account exists, a password reset email has been sent."
                ));

        verify(authService).forgotPassword(
                eq("tenant_example"),
                any(ForgotPasswordRequest.class)
        );
    }

    @Test
    void resetPasswordReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .header("X-Tenant-ID", "tenant_example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetToken\":\"valid-reset-token\","
                                + "\"newPassword\":\"NewSecurePassword123!\"}"))
                .andExpect(status().isNoContent());

        verify(authService).resetPassword(
                eq("tenant_example"),
                any(ResetPasswordRequest.class)
        );
    }

    private AuthResponse authResponse() {
        return new AuthResponse(
                "access-token",
                "new-refresh-token",
                900,
                1L,
                "user@example.com",
                "tenant_example",
                "USER"
        );
    }
}
