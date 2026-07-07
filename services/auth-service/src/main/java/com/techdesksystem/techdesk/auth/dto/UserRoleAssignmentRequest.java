package com.techdesksystem.techdesk.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Set;

public record UserRoleAssignmentRequest(
        @NotEmpty(message = "At least one role is required.")
        Set<@Positive(message = "Role id must be positive.") Long> roleIds,

        @NotNull(message = "Primary role id is required.")
        @Positive(message = "Primary role id must be positive.")
        Long primaryRoleId
) {
}
