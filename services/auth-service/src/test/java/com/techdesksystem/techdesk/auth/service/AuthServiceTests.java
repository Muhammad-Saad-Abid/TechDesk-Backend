package com.techdesksystem.techdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.dto.AuthResponse;
import com.techdesksystem.techdesk.auth.dto.LoginRequest;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import com.techdesksystem.techdesk.auth.security.PermissionService;
import com.techdesksystem.techdesk.auth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private PermissionService permissionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenMinutes(15);

        authService = new AuthService(
                userRepository,
                passwordEncoder,
                jwtUtil,
                jwtProperties,
                refreshTokenService,
                passwordResetService,
                permissionService
        );
    }

    @Test
    void loginNormalizesEmailAndReturnsTokenPair() {
        User user = enabledUser();
        LoginRequest request = loginRequest("  USER@Example.COM  ", "Password123!");

        given(userRepository.findByEmail("user@example.com"))
                .willReturn(Optional.of(user));
        given(passwordEncoder.matches("Password123!", user.getPasswordHash()))
                .willReturn(true);
        given(permissionService.effectivePermissionsForUser(42L))
                .willReturn(java.util.List.of(
                        "tickets:create",
                        "notifications:read"
                ));
        given(refreshTokenService.issueRefreshToken(user))
                .willReturn("refresh-token");
        given(jwtUtil.generateAccessToken(
                user,
                java.util.List.of("tickets:create", "notifications:read")
        )).willReturn("access-token");

        AuthResponse response = authService.login("tenant_example", request);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getExpiresInSeconds()).isEqualTo(900);
        assertThat(response.getTenantId()).isEqualTo("tenant_example");
    }

    @Test
    void unknownEmailUsesGenericInvalidCredentialsError() {
        LoginRequest request = loginRequest(
                "missing@example.com",
                "Password123!"
        );
        given(userRepository.findByEmail("missing@example.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("tenant_example", request))
                .isInstanceOfSatisfying(AuthException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(exception.getStatus().value()).isEqualTo(401);
                });
    }

    @Test
    void wrongPasswordUsesSameGenericInvalidCredentialsError() {
        User user = enabledUser();
        LoginRequest request = loginRequest(
                "user@example.com",
                "WrongPassword123!"
        );
        given(userRepository.findByEmail("user@example.com"))
                .willReturn(Optional.of(user));
        given(passwordEncoder.matches(
                "WrongPassword123!",
                user.getPasswordHash()
        )).willReturn(false);

        assertThatThrownBy(() -> authService.login("tenant_example", request))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("INVALID_CREDENTIALS")
                );
    }

    private User enabledUser() {
        User user = new User();
        user.setTenantId("tenant_example");
        user.setEmail("user@example.com");
        user.setPasswordHash("bcrypt-hash");
        user.setRole("EMPLOYEE");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 42L);

        return user;
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
