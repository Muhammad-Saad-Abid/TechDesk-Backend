package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.config.JwtProperties;
import com.techdesksystem.techdesk.auth.entity.RefreshToken;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.RefreshTokenRepository;
import com.techdesksystem.techdesk.auth.util.TokenHashUtil;
import com.techdesksystem.techdesk.auth.util.JwtUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenHashUtil tokenHashUtil;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final RefreshTokenBlacklistService refreshTokenBlacklistService;
    private final JwtUtil jwtUtil;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            TokenHashUtil tokenHashUtil,
            JwtProperties jwtProperties,
            RefreshTokenRevocationService refreshTokenRevocationService,
            RefreshTokenBlacklistService refreshTokenBlacklistService,
            JwtUtil jwtUtil
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenHashUtil = tokenHashUtil;
        this.jwtProperties = jwtProperties;
        this.refreshTokenRevocationService = refreshTokenRevocationService;
        this.refreshTokenBlacklistService = refreshTokenBlacklistService;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    public String issueRefreshToken(User user) {
        return createAndStoreRefreshToken(user, Instant.now());
    }

    @Transactional
    public RotationResult rotateRefreshToken(String rawRefreshToken) {
        if (!jwtUtil.isRefreshTokenValid(rawRefreshToken)) {
            throw AuthException.unauthorized(
                    "INVALID_REFRESH_TOKEN",
                    "Refresh token is invalid."
            );
        }

        String tokenHash = tokenHashUtil.hashToken(rawRefreshToken);

        if (refreshTokenBlacklistService.isBlacklisted(tokenHash)) {
            throw AuthException.unauthorized(
                    "INVALID_REFRESH_TOKEN",
                    "Refresh token is invalid."
            );
        }

        RefreshToken currentToken = refreshTokenRepository
                .findForUpdateByTokenHash(tokenHash)
                .orElseThrow(() ->
                        AuthException.unauthorized(
                                "INVALID_REFRESH_TOKEN",
                                "Refresh token is invalid."
                        )
                );

        Instant now = Instant.now();

        if (!currentToken.getExpiresAt().isAfter(now)) {
            throw AuthException.unauthorized(
                    "REFRESH_TOKEN_EXPIRED",
                    "Refresh token has expired."
            );
        }

        if (currentToken.getUsedAt() != null || currentToken.getRevokedAt() != null) {
            refreshTokenRevocationService.revokeAllActiveTokensAfterReplay(
                    currentToken.getUser().getId()
            );

            throw AuthException.unauthorized(
                    "REFRESH_TOKEN_REUSE_DETECTED",
                    "Refresh token reuse detected. All active sessions were revoked."
            );
        }

        currentToken.setUsedAt(now);
        refreshTokenRepository.save(currentToken);

        User user = currentToken.getUser();
        String newRawRefreshToken = createAndStoreRefreshToken(user, now);

        return new RotationResult(user, newRawRefreshToken);
    }

    /**
     * Revokes a refresh token and places its hash in Redis until expiration.
     * Unknown tokens are treated as already logged out to keep the operation
     * idempotent and avoid revealing token existence.
     *
     * @param tenantId trusted tenant context supplied by the gateway
     * @param rawRefreshToken refresh token presented by the client
     */
    @Transactional
    public void logout(String tenantId, String rawRefreshToken) {
        String tokenHash = tokenHashUtil.hashToken(rawRefreshToken);
        RefreshToken token = refreshTokenRepository
                .findForUpdateByTokenHash(tokenHash)
                .orElse(null);

        if (token == null) {
            return;
        }

        if (!tenantId.equals(token.getUser().getTenantId())) {
            throw AuthException.forbidden(
                    "REFRESH_TOKEN_TENANT_MISMATCH",
                    "Refresh token does not belong to the requested tenant."
            );
        }

        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            refreshTokenRepository.save(token);
        }

        refreshTokenBlacklistService.blacklist(tokenHash, token.getExpiresAt());
    }

    public void revokeAllActiveTokens(User user) {
        refreshTokenRevocationService.revokeAllActiveTokens(user.getId());
    }

    private String createAndStoreRefreshToken(User user, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(
                jwtProperties.getRefreshTokenDays(),
                ChronoUnit.DAYS
        );
        String rawToken = jwtUtil.generateRefreshToken(
                user,
                issuedAt,
                expiresAt
        );

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(tokenHashUtil.hashToken(rawToken));
        refreshToken.setExpiresAt(expiresAt);

        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    public record RotationResult(User user, String refreshToken) {
    }
}
