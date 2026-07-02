package com.techdesksystem.techdesk.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

@ExtendWith(MockitoExtension.class)
class RefreshTokenBlacklistServiceTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RefreshTokenBlacklistService blacklistService;

    @BeforeEach
    void setUp() {
        blacklistService = new RefreshTokenBlacklistService(redisTemplate);
    }

    @Test
    void blacklistEntryUsesOnlyTheTokensRemainingLifetime() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        Instant expiresAt = Instant.now().plusSeconds(60);
        ArgumentCaptor<Duration> ttlCaptor =
                ArgumentCaptor.forClass(Duration.class);

        blacklistService.blacklist("token-hash", expiresAt);

        verify(valueOperations).set(
                eq("auth:refresh:blacklist:token-hash"),
                eq("revoked"),
                ttlCaptor.capture()
        );
        assertThat(ttlCaptor.getValue())
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofSeconds(60))
                .isGreaterThan(Duration.ofSeconds(55));
    }

    @Test
    void expiredTokenDoesNotCreateAStaleBlacklistEntry() {
        blacklistService.blacklist(
                "expired-token-hash",
                Instant.now().minusSeconds(1)
        );

        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(valueOperations);
    }

    @Test
    void activeBlacklistEntryIsDetectedByItsHashedTokenKey() {
        given(redisTemplate.hasKey(
                "auth:refresh:blacklist:token-hash"
        )).willReturn(true);

        assertThat(blacklistService.isBlacklisted("token-hash")).isTrue();
        verify(redisTemplate).hasKey(
                "auth:refresh:blacklist:token-hash"
        );
    }
}
