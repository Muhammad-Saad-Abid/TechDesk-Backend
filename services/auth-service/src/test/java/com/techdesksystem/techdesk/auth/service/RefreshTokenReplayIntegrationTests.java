package com.techdesksystem.techdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.entity.RefreshToken;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.AuditLogRepository;
import com.techdesksystem.techdesk.auth.repository.RefreshTokenRepository;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import com.techdesksystem.techdesk.auth.util.TokenHashUtil;
import com.techdesksystem.techdesk.auth.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@DataJpaTest
@Import({
        RefreshTokenService.class,
        RefreshTokenRevocationService.class,
        SecurityAuditService.class,
        JwtUtil.class,
        TokenHashUtil.class
})
@EnableConfigurationProperties(JwtProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenReplayIntegrationTests {

    @MockitoBean
    private RefreshTokenBlacklistService refreshTokenBlacklistService;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLogRepository auditLogRepository;
    private final RefreshTokenService refreshTokenService;
    private final TokenHashUtil tokenHashUtil;

    @Autowired
    RefreshTokenReplayIntegrationTests(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuditLogRepository auditLogRepository,
            RefreshTokenService refreshTokenService,
            TokenHashUtil tokenHashUtil
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLogRepository = auditLogRepository;
        this.refreshTokenService = refreshTokenService;
        this.tokenHashUtil = tokenHashUtil;
    }

    @Test
    void replayingUsedTokenPermanentlyRevokesNewerActiveToken() {
        User user = createUser();
        String firstToken = refreshTokenService.issueRefreshToken(user);

        RefreshTokenService.RotationResult rotation =
                refreshTokenService.rotateRefreshToken(firstToken);

        assertThatThrownBy(() ->
                refreshTokenService.rotateRefreshToken(firstToken)
        )
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("reuse detected");

        RefreshToken newerToken = refreshTokenRepository
                .findByTokenHash(tokenHashUtil.hashToken(rotation.refreshToken()))
                .orElseThrow();

        assertThat(newerToken.getRevokedAt()).isNotNull();
        assertThat(auditLogRepository.findAll())
                .singleElement()
                .satisfies(auditLog -> {
                    assertThat(auditLog.getAction()).isEqualTo(
                            SecurityAuditService.REFRESH_TOKEN_REUSE_DETECTED
                    );
                    assertThat(auditLog.getEntityType()).isEqualTo("AUTH_USER");
                    assertThat(auditLog.getEntityId())
                            .isEqualTo(user.getId().toString());
                });
    }

    @Test
    void ordinaryRevocationDoesNotCreateReplayAuditEvent() {
        User user = createUser("ordinary-revocation@example.com");
        String token = refreshTokenService.issueRefreshToken(user);
        long auditCountBeforeRevocation = auditLogRepository.count();

        refreshTokenService.revokeAllActiveTokens(user);

        RefreshToken persistedToken = refreshTokenRepository
                .findByTokenHash(tokenHashUtil.hashToken(token))
                .orElseThrow();

        assertThat(persistedToken.getRevokedAt()).isNotNull();
        assertThat(auditLogRepository.count())
                .isEqualTo(auditCountBeforeRevocation);
    }

    @Test
    void concurrentRefreshAllowsOnlyOneRotationAndRevokesTheWinner() throws Exception {
        User user = createUser("concurrent-replay@example.com");
        String originalToken = refreshTokenService.issueRefreshToken(user);
        long replayAuditCountBefore = replayAuditCount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<RotationAttempt>> attempts = List.of(
                    submitRotation(executor, ready, start, originalToken),
                    submitRotation(executor, ready, start, originalToken)
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RotationAttempt> results = attempts.stream()
                    .map(this::awaitAttempt)
                    .toList();

            assertThat(results).filteredOn(RotationAttempt::succeeded)
                    .hasSize(1);
            assertThat(results).filteredOn(result ->
                            "REFRESH_TOKEN_REUSE_DETECTED".equals(result.errorCode()))
                    .hasSize(1);

            String rotatedToken = results.stream()
                    .filter(RotationAttempt::succeeded)
                    .map(RotationAttempt::refreshToken)
                    .findFirst()
                    .orElseThrow();
            RefreshToken persistedWinner = refreshTokenRepository
                    .findByTokenHash(tokenHashUtil.hashToken(rotatedToken))
                    .orElseThrow();

            assertThat(persistedWinner.getRevokedAt()).isNotNull();
            assertThat(replayAuditCount())
                    .isEqualTo(replayAuditCountBefore + 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<RotationAttempt> submitRotation(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            String refreshToken
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent rotation did not start.");
            }

            try {
                RefreshTokenService.RotationResult result =
                        refreshTokenService.rotateRefreshToken(refreshToken);
                return RotationAttempt.success(result.refreshToken());
            } catch (AuthException exception) {
                return RotationAttempt.failure(exception.getCode());
            }
        });
    }

    private RotationAttempt awaitAttempt(Future<RotationAttempt> attempt) {
        try {
            return attempt.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent refresh attempt failed.", exception);
        }
    }

    private long replayAuditCount() {
        return auditLogRepository.findAll().stream()
                .filter(auditLog ->
                        SecurityAuditService.REFRESH_TOKEN_REUSE_DETECTED
                                .equals(auditLog.getAction()))
                .count();
    }

    private record RotationAttempt(
            String refreshToken,
            String errorCode
    ) {
        static RotationAttempt success(String refreshToken) {
            return new RotationAttempt(refreshToken, null);
        }

        static RotationAttempt failure(String errorCode) {
            return new RotationAttempt(null, errorCode);
        }

        boolean succeeded() {
            return refreshToken != null;
        }
    }

    private User createUser() {
        return createUser("replay-test@example.com");
    }

    private User createUser(String email) {
        User user = new User();
        user.setTenantId("tenant_replay_test");
        user.setEmail(email);
        user.setPasswordHash("not-used-by-this-test");
        user.setRole("USER");
        user.setEnabled(true);

        return userRepository.save(user);
    }
}
