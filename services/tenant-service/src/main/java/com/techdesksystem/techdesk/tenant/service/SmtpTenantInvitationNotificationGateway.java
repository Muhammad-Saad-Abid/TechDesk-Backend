package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.config.TenantProvisioningProperties;
import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class SmtpTenantInvitationNotificationGateway
        implements TenantInvitationNotificationGateway {

    private final JavaMailSender mailSender;
    private final TenantProvisioningProperties properties;

    public SmtpTenantInvitationNotificationGateway(
            JavaMailSender mailSender,
            TenantProvisioningProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendInvitation(
            TenantNotificationOutbox event,
            String signedInvitationToken
    ) {
        String invitationUrl = properties.invitationUrl()
                + "?token="
                + URLEncoder.encode(
                        signedInvitationToken,
                        StandardCharsets.UTF_8
                );
        String portalUrl = properties.portalUrlTemplate()
                .formatted(event.getTenantSlug());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(event.getRecipientEmail());
        message.setSubject("Welcome to TechDesk - " + event.getTenantName());
        message.setText("Hello " + event.getRecipientName() + ",\n\n"
                + "Your company workspace is ready.\n"
                + "Portal: " + portalUrl + "\n"
                + "Role: COMPANY_ADMIN\n\n"
                + "Create your password using this one-time invitation:\n"
                + invitationUrl + "\n\n"
                + "This invitation expires at "
                + event.getInvitationExpiresAt() + ".\n");

        mailSender.send(message);
    }
}
