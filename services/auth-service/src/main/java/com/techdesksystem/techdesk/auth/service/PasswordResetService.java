package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.config.PasswordResetProperties;
import com.techdesksystem.techdesk.auth.entity.PasswordResetToken;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.PasswordResetTokenRepository;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import com.techdesksystem.techdesk.auth.util.TokenHashUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TokenHashUtil tokenHashUtil;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetProperties properties;
    private final AuthMailService authMailService;
    private final RefreshTokenService refreshTokenService;
    private final SecurityAuditService securityAuditService;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            TokenHashUtil tokenHashUtil,
            PasswordEncoder passwordEncoder,
            PasswordResetProperties properties,
            AuthMailService authMailService,
            RefreshTokenService refreshTokenService,
            SecurityAuditService securityAuditService
    ) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.tokenHashUtil = tokenHashUtil;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.authMailService = authMailService;
        this.refreshTokenService = refreshTokenService;
        this.securityAuditService = securityAuditService;
    }

    /**
     * Issues a new reset token without revealing whether the requested account
     * exists. Any older unused links for the account are invalidated first.
     *
     * @param tenantId trusted tenant context
     * @param email account email supplied by the client
     */
    @Transactional
    public void requestPasswordReset(String tenantId, String email) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository
                .findByTenantIdAndEmail(tenantId, normalizedEmail)
                .orElse(null);

        if (user == null || !user.isEnabled()) {
            return;
        }

        Instant now = Instant.now();
        List<PasswordResetToken> previousTokens =
                passwordResetTokenRepository.findAllByUserAndUsedAtIsNull(user);

        previousTokens.forEach(token -> token.setUsedAt(now));
        passwordResetTokenRepository.saveAll(previousTokens);

        String rawToken = tokenHashUtil.generateRawToken();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(tokenHashUtil.hashToken(rawToken));
        resetToken.setExpiresAt(now.plus(
                properties.getExpirationMinutes(),
                ChronoUnit.MINUTES
        ));

        passwordResetTokenRepository.saveAndFlush(resetToken);
        authMailService.sendPasswordResetEmail(
                user.getEmail(),
                rawToken,
                user.getTenantId()
        );
    }

    /**
     * Consumes a reset token exactly once, updates the password, revokes all
     * refresh sessions, and records the security-sensitive change.
     *
     * @param tenantId trusted tenant context
     * @param rawToken raw token received from the reset link
     * @param newPassword replacement password
     */
    @Transactional
    public void resetPassword(
            String tenantId,
            String rawToken,
            String newPassword
    ) {
        String tokenHash = tokenHashUtil.hashToken(rawToken);
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(this::invalidResetToken);

        User user = resetToken.getUser();

        if (!tenantId.equals(user.getTenantId())) {
            throw invalidResetToken();
        }

        Instant now = Instant.now();

        if (resetToken.getUsedAt() != null) {
            throw invalidResetToken();
        }

        if (!resetToken.getExpiresAt().isAfter(now)) {
            throw AuthException.badRequest(
                    "RESET_TOKEN_EXPIRED",
                    "Password reset token has expired."
            );
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        resetToken.setUsedAt(now);

        userRepository.save(user);
        passwordResetTokenRepository.save(resetToken);
        refreshTokenService.revokeAllActiveTokens(user);
        securityAuditService.recordPasswordReset(user.getId());
    }

    private AuthException invalidResetToken() {
        return AuthException.badRequest(
                "INVALID_RESET_TOKEN",
                "Password reset token is invalid or has already been used."
        );
    }
}
