package com.techdesksystem.techdesk.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UserInvitationRequest(
        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be valid.")
        @Size(max = 255, message = "Email must not exceed 255 characters.")
        String email,

        @Size(max = 100, message = "First name must not exceed 100 characters.")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters.")
        String lastName,

        @Positive(message = "Department id must be positive.")
        Long departmentId,

        Set<@Positive(message = "Role id must be positive.") Long> roleIds,

        @Positive(message = "Primary role id must be positive.")
        Long primaryRoleId
) {
}
