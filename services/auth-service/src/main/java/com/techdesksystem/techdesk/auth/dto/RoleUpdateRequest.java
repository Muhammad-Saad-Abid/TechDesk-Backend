package com.techdesksystem.techdesk.auth.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RoleUpdateRequest(
        @Size(max = 100, message = "Role display name must be at most 100 characters.")
        String displayName,

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
