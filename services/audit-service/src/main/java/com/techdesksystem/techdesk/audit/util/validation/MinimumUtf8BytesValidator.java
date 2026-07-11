package com.techdesksystem.techdesk.audit.util.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public final class MinimumUtf8BytesValidator
        implements ConstraintValidator<MinimumUtf8Bytes, CharSequence> {

    private int minimumBytes;

    @Override
    public void initialize(MinimumUtf8Bytes constraint) {
        minimumBytes = constraint.value();
        if (minimumBytes < 0) {
            throw new IllegalArgumentException("Minimum UTF-8 byte length cannot be negative.");
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length >= minimumBytes;
    }
}
