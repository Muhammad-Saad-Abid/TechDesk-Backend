package com.techdesksystem.techdesk.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RefreshTokenBlacklistService {

    private static final String KEY_PREFIX = "auth:refresh:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Blacklists a hashed refresh token only for its remaining lifetime, which
     * prevents stale blacklist keys from accumulating indefinitely.
     *
     * @param tokenHash SHA-256 hash of the refresh token
     * @param expiresAt refresh token expiration time
     */
    public void blacklist(String tokenHash, Instant expiresAt) {
        Duration remainingLifetime = Duration.between(Instant.now(), expiresAt);

        if (!remainingLifetime.isNegative() && !remainingLifetime.isZero()) {
            redisTemplate.opsForValue().set(
                    key(tokenHash),
                    "revoked",
                    remainingLifetime
            );
        }
    }

    /**
     * Checks whether a hashed refresh token has been explicitly blacklisted.
     *
     * @param tokenHash SHA-256 hash of the refresh token
     * @return true when Redis contains an active blacklist entry
     */
    public boolean isBlacklisted(String tokenHash) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(tokenHash)));
    }

    private String key(String tokenHash) {
        return KEY_PREFIX + tokenHash;
    }
}
