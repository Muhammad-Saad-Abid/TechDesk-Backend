package com.techdesksystem.techdesk.auth.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentUpdateRequest(
        @Size(max = 120, message = "Department name must be at most 120 characters.")
        String name,

        @Size(max = 40, message = "Department code must be at most 40 characters.")
        @Pattern(
                regexp = "^[A-Za-z][A-Za-z0-9_]{0,39}$",
                message = "Department code must start with a letter and contain only letters, numbers, or underscores."
        )
        String code,

        Boolean active
) {
}
