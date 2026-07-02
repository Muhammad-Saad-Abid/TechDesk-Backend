package com.techdesksystem.techdesk.auth.controller;

import com.techdesksystem.techdesk.auth.dto.AuthResponse;
import com.techdesksystem.techdesk.auth.dto.ForgotPasswordRequest;
import com.techdesksystem.techdesk.auth.dto.LoginRequest;
import com.techdesksystem.techdesk.auth.dto.LogoutRequest;
import com.techdesksystem.techdesk.auth.dto.MessageResponse;
import com.techdesksystem.techdesk.auth.dto.RefreshTokenRequest;
import com.techdesksystem.techdesk.auth.dto.RegisterRequest;
import com.techdesksystem.techdesk.auth.dto.ResetPasswordRequest;
import com.techdesksystem.techdesk.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody RegisterRequest request
    ) {
        AuthResponse response = authService.register(tenantId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody LoginRequest request
    ) {
        return ResponseEntity.ok(authService.login(tenantId, request));
    }

    @PostMapping({"/refresh-token", "/refresh"})
    public ResponseEntity<AuthResponse> refresh(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return ResponseEntity.ok(authService.refresh(tenantId, request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody LogoutRequest request
    ) {
        authService.logout(tenantId, request);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        authService.forgotPassword(tenantId, request);

        return ResponseEntity.accepted().body(new MessageResponse(
                "If an eligible account exists, a password reset email has been sent."
        ));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        authService.resetPassword(tenantId, request);

        return ResponseEntity.noContent().build();
    }
}
