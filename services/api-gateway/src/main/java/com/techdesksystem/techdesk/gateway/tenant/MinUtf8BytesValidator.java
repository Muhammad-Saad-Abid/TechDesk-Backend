package com.techdesksystem.techdesk.gateway.tenant;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class MinUtf8BytesValidator
        implements ConstraintValidator<MinUtf8Bytes, CharSequence> {

    private int minimumBytes;

    @Override
    public void initialize(MinUtf8Bytes constraintAnnotation) {
        minimumBytes = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(
            CharSequence value,
            ConstraintValidatorContext context
    ) {
        if (value == null) {
            return true;
        }

        return value.toString().getBytes(StandardCharsets.UTF_8).length
                >= minimumBytes;
    }
}
