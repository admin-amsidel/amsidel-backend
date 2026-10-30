/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PinValidator implements ConstraintValidator<Pin, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PinPolicy.isValid(value);
    }
}
