package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.entity.RefreshToken;
import com.techdesksystem.techdesk.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityAuditService securityAuditService;

    public RefreshTokenRevocationService(
            RefreshTokenRepository refreshTokenRepository,
            SecurityAuditService securityAuditService
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityAuditService = securityAuditService;
    }

    /**
     * Revokes every active refresh token owned by a user in an independent
     * transaction. The independent transaction is required for security
     * responses that deliberately throw after revocation, such as replay
     * detection and cross-tenant token use.
     *
     * @param userId database identifier of the affected user
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllActiveTokens(Long userId) {
        revokeActiveTokens(userId);
    }

    /**
     * Revokes all active refresh tokens and records the replay event in one
     * independent transaction.
     *
     * @param userId database identifier of the affected user
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllActiveTokensAfterReplay(Long userId) {
        revokeActiveTokens(userId);
        securityAuditService.recordRefreshTokenReplay(userId);
    }

    private void revokeActiveTokens(Long userId) {
        Instant now = Instant.now();
        List<RefreshToken> userTokens =
                refreshTokenRepository.findAllByUserId(userId);

        for (RefreshToken token : userTokens) {
            if (token.isActive()) {
                token.setRevokedAt(now);
            }
        }

        refreshTokenRepository.saveAll(userTokens);
    }
}
