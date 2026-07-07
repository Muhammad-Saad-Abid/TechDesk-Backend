package com.techdesksystem.techdesk.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RoleCreateRequest(
        @NotBlank(message = "Role name is required.")
        @Size(min = 3, max = 80, message = "Role name must be 3 to 80 characters.")
        @Pattern(
                regexp = "^[A-Za-z][A-Za-z0-9_]{2,79}$",
                message = "Role name must start with a letter and contain only letters, numbers, or underscores."
        )
        String name,

        @NotBlank(message = "Role display name is required.")
        @Size(max = 100, message = "Role display name must be at most 100 characters.")
        String displayName,

        @NotBlank(message = "Role description is required.")
        @Size(max = 255, message = "Role description must be at most 255 characters.")
        String description,

        Set<
                @Pattern(
                        regexp = "^[a-z][a-z0-9_:.-]{2,99}$",
                        message = "Permission code is invalid."
                )
                String
                > permissionCodes
) {
}
