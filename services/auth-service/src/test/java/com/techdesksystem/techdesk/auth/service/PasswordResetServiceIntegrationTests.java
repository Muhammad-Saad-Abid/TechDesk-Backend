package com.techdesksystem.techdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.config.PasswordResetProperties;
import com.techdesksystem.techdesk.auth.entity.PasswordResetToken;
import com.techdesksystem.techdesk.auth.entity.RefreshToken;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.entity.UserStatus;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.AuditLogRepository;
import com.techdesksystem.techdesk.auth.repository.PasswordResetTokenRepository;
import com.techdesksystem.techdesk.auth.repository.RefreshTokenRepository;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import com.techdesksystem.techdesk.auth.util.TokenHashUtil;
import com.techdesksystem.techdesk.auth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@DataJpaTest
@Import({
        PasswordResetService.class,
        RefreshTokenService.class,
        RefreshTokenRevocationService.class,
        SecurityAuditService.class,
        JwtUtil.class,
        TokenHashUtil.class,
        PasswordResetServiceIntegrationTests.PasswordEncoderTestConfig.class
})
@EnableConfigurationProperties({
        JwtProperties.class,
        PasswordResetProperties.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetServiceIntegrationTests {

    @MockitoBean
    private AuthMailService authMailService;

    @MockitoBean
    private RefreshTokenBlacklistService refreshTokenBlacklistService;

    private final PasswordResetService passwordResetService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLogRepository auditLogRepository;
    private final TokenHashUtil tokenHashUtil;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    PasswordResetServiceIntegrationTests(
            PasswordResetService passwordResetService,
            RefreshTokenService refreshTokenService,
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuditLogRepository auditLogRepository,
            TokenHashUtil tokenHashUtil,
            PasswordEncoder passwordEncoder
    ) {
        this.passwordResetService = passwordResetService;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLogRepository = auditLogRepository;
        this.tokenHashUtil = tokenHashUtil;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    void resetTokenIsHashedSingleUseAndRevokesActiveSessions() {
        User user = createUser("password-reset@example.com");
        String refreshToken = refreshTokenService.issueRefreshToken(user);

        passwordResetService.requestPasswordReset(
                user.getTenantId(),
                user.getEmail()
        );

        ArgumentCaptor<String> rawTokenCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(authMailService).sendPasswordResetEmail(
                eq(user.getEmail()),
                rawTokenCaptor.capture(),
                eq(user.getTenantId())
        );

        String rawResetToken = rawTokenCaptor.getValue();
        String resetTokenHash = tokenHashUtil.hashToken(rawResetToken);
        PasswordResetToken storedResetToken = passwordResetTokenRepository
                .findFirstByTokenHash(resetTokenHash)
                .orElseThrow();

        assertThat(storedResetToken.getTokenHash()).isNotEqualTo(rawResetToken);

        passwordResetService.resetPassword(
                user.getTenantId(),
                rawResetToken,
                "NewSecurePassword123!"
        );

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        RefreshToken storedRefreshToken = refreshTokenRepository
                .findByTokenHash(tokenHashUtil.hashToken(refreshToken))
                .orElseThrow();
        PasswordResetToken consumedToken = passwordResetTokenRepository
                .findFirstByTokenHash(resetTokenHash)
                .orElseThrow();

        assertThat(passwordEncoder.matches(
                "NewSecurePassword123!",
                updatedUser.getPasswordHash()
        )).isTrue();
        assertThat(consumedToken.getUsedAt()).isNotNull();
        assertThat(storedRefreshToken.getRevokedAt()).isNotNull();
        assertThat(auditLogRepository.findAll())
                .extracting("action")
                .contains(SecurityAuditService.PASSWORD_RESET_COMPLETED);

        assertThatThrownBy(() -> passwordResetService.resetPassword(
                user.getTenantId(),
                rawResetToken,
                "AnotherPassword123!"
        ))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void unknownEmailDoesNotRevealAccountOrSendEmail() {
        long tokenCountBeforeRequest = passwordResetTokenRepository.count();

        passwordResetService.requestPasswordReset(
                "tenant_password_test",
                "missing@example.com"
        );

        assertThat(passwordResetTokenRepository.count())
                .isEqualTo(tokenCountBeforeRequest);
        verify(authMailService, never()).sendPasswordResetEmail(
                eq("missing@example.com"),
                org.mockito.ArgumentMatchers.anyString(),
                eq("tenant_password_test")
        );
    }

    @Test
    void invitedUserSetsPasswordAndBecomesActive() {
        User user = createUser("invited-reset@example.com");
        user.setStatus(UserStatus.INVITED);
        user.setEnabled(false);
        user = userRepository.saveAndFlush(user);

        passwordResetService.sendInvitation(user);

        ArgumentCaptor<String> rawTokenCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(authMailService).sendUserInvitationEmail(
                eq(user.getEmail()),
                rawTokenCaptor.capture(),
                eq(user.getTenantId())
        );

        passwordResetService.resetPassword(
                user.getTenantId(),
                rawTokenCaptor.getValue(),
                "InvitedSecurePassword123!"
        );

        User activatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(activatedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(activatedUser.isEnabled()).isTrue();
        assertThat(passwordEncoder.matches(
                "InvitedSecurePassword123!",
                activatedUser.getPasswordHash()
        )).isTrue();
    }

    @Test
    void expiredResetTokenCannotChangePassword() {
        User user = createUser("expired-reset@example.com");
        String rawToken = tokenHashUtil.generateRawToken();
        PasswordResetToken expiredToken = new PasswordResetToken();
        expiredToken.setUser(user);
        expiredToken.setTokenHash(tokenHashUtil.hashToken(rawToken));
        expiredToken.setExpiresAt(Instant.now().minusSeconds(1));
        passwordResetTokenRepository.saveAndFlush(expiredToken);
        long auditCountBefore = auditLogRepository.count();

        assertThatThrownBy(() -> passwordResetService.resetPassword(
                user.getTenantId(),
                rawToken,
                "ShouldNeverBeStored123!"
        ))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("RESET_TOKEN_EXPIRED"));

        User unchangedUser = userRepository.findById(user.getId()).orElseThrow();
        PasswordResetToken unchangedToken = passwordResetTokenRepository
                .findFirstByTokenHash(tokenHashUtil.hashToken(rawToken))
                .orElseThrow();

        assertThat(passwordEncoder.matches(
                "OldPassword123!",
                unchangedUser.getPasswordHash()
        )).isTrue();
        assertThat(unchangedToken.getUsedAt()).isNull();
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);
    }

    @Test
    void concurrentResetConsumesTokenOnlyOnce() throws Exception {
        User user = createUser("concurrent-reset@example.com");
        passwordResetService.requestPasswordReset(
                user.getTenantId(),
                user.getEmail()
        );

        ArgumentCaptor<String> rawTokenCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(authMailService).sendPasswordResetEmail(
                eq(user.getEmail()),
                rawTokenCaptor.capture(),
                eq(user.getTenantId())
        );

        String rawToken = rawTokenCaptor.getValue();
        long auditCountBefore = passwordResetAuditCount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<ResetAttempt>> attempts = List.of(
                    submitReset(
                            executor,
                            ready,
                            start,
                            user.getTenantId(),
                            rawToken,
                            "ConcurrentWinnerOne123!"
                    ),
                    submitReset(
                            executor,
                            ready,
                            start,
                            user.getTenantId(),
                            rawToken,
                            "ConcurrentWinnerTwo123!"
                    )
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ResetAttempt> results = attempts.stream()
                    .map(this::awaitReset)
                    .toList();

            assertThat(results).filteredOn(ResetAttempt::succeeded)
                    .hasSize(1);
            assertThat(results).filteredOn(result ->
                            "INVALID_RESET_TOKEN".equals(result.errorCode()))
                    .hasSize(1);

            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            PasswordResetToken consumedToken = passwordResetTokenRepository
                    .findFirstByTokenHash(tokenHashUtil.hashToken(rawToken))
                    .orElseThrow();

            assertThat(
                    passwordEncoder.matches(
                            "ConcurrentWinnerOne123!",
                            updatedUser.getPasswordHash()
                    ) || passwordEncoder.matches(
                            "ConcurrentWinnerTwo123!",
                            updatedUser.getPasswordHash()
                    )
            ).isTrue();
            assertThat(consumedToken.getUsedAt()).isNotNull();
            assertThat(passwordResetAuditCount())
                    .isEqualTo(auditCountBefore + 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<ResetAttempt> submitReset(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            String tenantId,
            String rawToken,
            String newPassword
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent reset did not start.");
            }

            try {
                passwordResetService.resetPassword(
                        tenantId,
                        rawToken,
                        newPassword
                );
                return ResetAttempt.success();
            } catch (AuthException exception) {
                return ResetAttempt.failure(exception.getCode());
            }
        });
    }

    private ResetAttempt awaitReset(Future<ResetAttempt> attempt) {
        try {
            return attempt.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent password reset failed.", exception);
        }
    }

    private long passwordResetAuditCount() {
        return auditLogRepository.findAll().stream()
                .filter(auditLog ->
                        SecurityAuditService.PASSWORD_RESET_COMPLETED
                                .equals(auditLog.getAction()))
                .count();
    }

    private record ResetAttempt(boolean succeeded, String errorCode) {
        static ResetAttempt success() {
            return new ResetAttempt(true, null);
        }

        static ResetAttempt failure(String errorCode) {
            return new ResetAttempt(false, errorCode);
        }
    }

    private User createUser(String email) {
        User user = new User();
        user.setTenantId("tenant_password_test");
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("OldPassword123!"));
        user.setRole("USER");
        user.setEnabled(true);

        return userRepository.save(user);
    }

    @TestConfiguration
    static class PasswordEncoderTestConfig {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }
}
