package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.dto.AuthResponse;
import com.techdesksystem.techdesk.auth.dto.ForgotPasswordRequest;
import com.techdesksystem.techdesk.auth.dto.LoginRequest;
import com.techdesksystem.techdesk.auth.dto.LogoutRequest;
import com.techdesksystem.techdesk.auth.dto.RefreshTokenRequest;
import com.techdesksystem.techdesk.auth.dto.RegisterRequest;
import com.techdesksystem.techdesk.auth.dto.ResetPasswordRequest;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import com.techdesksystem.techdesk.auth.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern TENANT_ID_PATTERN =
            Pattern.compile("^tenant_[a-z][a-z0-9_]{0,55}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            JwtProperties jwtProperties,
            RefreshTokenService refreshTokenService,
            PasswordResetService passwordResetService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
        this.passwordResetService = passwordResetService;
    }

    @Transactional
    public AuthResponse register(String tenantId, RegisterRequest request) {
        validateTenantId(tenantId);

        String normalizedEmail = normalizeEmail(request.getEmail());

        if (userRepository.existsByTenantIdAndEmail(tenantId, normalizedEmail)) {
            throw AuthException.conflict(
                    "ACCOUNT_ALREADY_EXISTS",
                    "An account with this email already exists for this tenant."
            );
        }

        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(trimToNull(request.getFirstName()));
        user.setLastName(trimToNull(request.getLastName()));
        user.setRole("EMPLOYEE");
        user.setEnabled(true);

        User savedUser;

        try {
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw AuthException.conflict(
                    "ACCOUNT_ALREADY_EXISTS",
                    "An account with this email already exists for this tenant."
            );
        }

        return buildAuthResponse(
                savedUser,
                refreshTokenService.issueRefreshToken(savedUser)
        );
    }

    @Transactional
    public AuthResponse login(String tenantId, LoginRequest request) {
        validateTenantId(tenantId);

        String normalizedEmail = normalizeEmail(request.getEmail());

        User user = userRepository.findByTenantIdAndEmail(tenantId, normalizedEmail)
                .orElseThrow(() ->
                        AuthException.unauthorized(
                                "INVALID_CREDENTIALS",
                                "Invalid email or password."
                        )
                );

        if (!user.isEnabled()) {
            throw AuthException.forbidden(
                    "ACCOUNT_DISABLED",
                    "This user account is disabled."
            );
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw AuthException.unauthorized(
                    "INVALID_CREDENTIALS",
                    "Invalid email or password."
            );
        }

        return buildAuthResponse(
                user,
                refreshTokenService.issueRefreshToken(user)
        );
    }

    @Transactional
    public AuthResponse refresh(String tenantId, RefreshTokenRequest request) {
        validateTenantId(tenantId);

        RefreshTokenService.RotationResult rotationResult =
                refreshTokenService.rotateRefreshToken(request.getRefreshToken());

        User user = rotationResult.user();

        if (!tenantId.equals(user.getTenantId())) {
            refreshTokenService.revokeAllActiveTokens(user);

            throw AuthException.forbidden(
                    "REFRESH_TOKEN_TENANT_MISMATCH",
                    "Refresh token does not belong to the requested tenant."
            );
        }

        if (!user.isEnabled()) {
            throw AuthException.forbidden(
                    "ACCOUNT_DISABLED",
                    "This user account is disabled."
            );
        }

        return buildAuthResponse(user, rotationResult.refreshToken());
    }

    public void logout(String tenantId, LogoutRequest request) {
        validateTenantId(tenantId);
        refreshTokenService.logout(tenantId, request.getRefreshToken());
    }

    public void forgotPassword(
            String tenantId,
            ForgotPasswordRequest request
    ) {
        validateTenantId(tenantId);
        passwordResetService.requestPasswordReset(tenantId, request.getEmail());
    }

    public void resetPassword(
            String tenantId,
            ResetPasswordRequest request
    ) {
        validateTenantId(tenantId);
        passwordResetService.resetPassword(
                tenantId,
                request.getResetToken(),
                request.getNewPassword()
        );
    }

    private AuthResponse buildAuthResponse(User user, String refreshToken) {
        String accessToken = jwtUtil.generateAccessToken(user);

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtProperties.getAccessTokenMinutes() * 60,
                user.getId(),
                user.getEmail(),
                user.getTenantId(),
                user.getRole()
        );
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null
                || !TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            throw AuthException.badRequest(
                    "INVALID_TENANT_CONTEXT",
                    "A valid tenant context is required. Use the API Gateway."
            );
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
