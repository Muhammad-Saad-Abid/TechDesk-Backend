package com.techdesksystem.techdesk.auth.dto;

import com.techdesksystem.techdesk.auth.entity.UserStatus;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
        @Size(max = 100, message = "First name must not exceed 100 characters.")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters.")
        String lastName,

        @Positive(message = "Department id must be positive.")
        Long departmentId,

        Boolean clearDepartment,

        UserStatus status
) {
}
