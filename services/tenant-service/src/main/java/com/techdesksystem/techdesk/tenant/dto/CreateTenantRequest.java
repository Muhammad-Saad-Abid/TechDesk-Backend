package com.techdesksystem.techdesk.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank
        @Size(max = 150)
        String name,

        @NotBlank
        @Size(max = 80)
        String slug,

        @NotBlank
        @Email
        @Size(max = 255)
        String adminEmail,

        @NotBlank
        @Size(max = 100)
        String adminFirstName,

        @NotBlank
        @Size(max = 100)
        String adminLastName
) {
}
