package com.techdesksystem.techdesk.auth.service;

import com.techdesksystem.techdesk.auth.config.PasswordResetProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class AuthMailService {

    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;

    public AuthMailService(
            JavaMailSender mailSender,
            PasswordResetProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * Sends a password-reset link containing the raw, single-use token. The
     * raw token is never persisted or written to application logs.
     *
     * @param recipient account email address
     * @param resetToken raw password-reset token
     * @param tenantId schema identifier of the account tenant
     */
    public void sendPasswordResetEmail(
            String recipient,
            String resetToken,
            String tenantId
    ) {
        String resetLink = properties.getResetUrl()
                + "?token=" + encode(resetToken)
                + "&tenant=" + encode(tenantId);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(recipient);
        message.setSubject("Reset your TechDesk password");
        message.setText(
                "Use the link below to reset your TechDesk password. "
                        + "This link expires in "
                        + properties.getExpirationMinutes()
                        + " minutes.\n\n"
                        + resetLink
                        + "\n\nIf you did not request this, you can ignore this email."
        );

        mailSender.send(message);
    }

    /**
     * Sends an invitation link that lets a newly invited user set their first
     * password. The invitation token is stored only as a hash server-side.
     *
     * @param recipient invited account email address
     * @param invitationToken raw, single-use invitation token
     * @param tenantId schema identifier of the invited account tenant
     */
    public void sendUserInvitationEmail(
            String recipient,
            String invitationToken,
            String tenantId
    ) {
        String invitationLink = properties.getResetUrl()
                + "?token=" + encode(invitationToken)
                + "&tenant=" + encode(tenantId)
                + "&mode=invite";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(recipient);
        message.setSubject("You have been invited to TechDesk");
        message.setText(
                "You have been invited to join TechDesk. "
                        + "Use the link below to set your password. "
                        + "This invitation expires in "
                        + properties.getExpirationMinutes()
                        + " minutes.\n\n"
                        + invitationLink
                        + "\n\nIf you were not expecting this invitation, "
                        + "you can ignore this email."
        );

        mailSender.send(message);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
