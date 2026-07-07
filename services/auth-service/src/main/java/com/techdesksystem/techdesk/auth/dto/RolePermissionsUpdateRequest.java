package com.techdesksystem.techdesk.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

public record RolePermissionsUpdateRequest(
        @NotNull(message = "Permission codes are required.")
        Set<
                @Pattern(
                        regexp = "^[a-z][a-z0-9_:.-]{2,99}$",
                        message = "Permission code is invalid."
                )
                String
                > permissionCodes
) {
}
