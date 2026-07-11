package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.config.PasswordResetProperties;
import com.techdesksystem.techdesk.auth.entity.PasswordResetToken;
import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.entity.UserStatus;
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
                .findByEmail(normalizedEmail)
                .orElse(null);

        if (user == null || !user.isEnabled()) {
            return;
        }
        user.setTenantId(tenantId);

        String rawToken = issueToken(user);
        authMailService.sendPasswordResetEmail(
                user.getEmail(),
                rawToken,
                user.getTenantId()
        );
    }

    /**
     * Issues an invitation token for an already-created INVITED user. This uses
     * the same hashed, single-use token storage as password reset so onboarding
     * links never persist raw secrets.
     *
     * @param invitedUser tenant-local invited user
     */
    @Transactional
    public void sendInvitation(User invitedUser) {
        if (invitedUser.getStatus() != UserStatus.INVITED) {
            throw AuthException.badRequest(
                    "USER_NOT_INVITED",
                    "Only invited users can receive an onboarding invitation."
            );
        }

        String rawToken = issueToken(invitedUser);
        authMailService.sendUserInvitationEmail(
                invitedUser.getEmail(),
                rawToken,
                invitedUser.getTenantId()
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
        user.setTenantId(tenantId);

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

        if (user.getStatus() == UserStatus.SUSPENDED
                || user.getStatus() == UserStatus.DISABLED) {
            throw AuthException.forbidden(
                    "ACCOUNT_DISABLED",
                    "This user account is not active."
            );
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        if (user.getStatus() == UserStatus.INVITED) {
            user.setStatus(UserStatus.ACTIVE);
            user.setEnabled(true);
        }
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

    private String issueToken(User user) {
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
        return rawToken;
    }
}
