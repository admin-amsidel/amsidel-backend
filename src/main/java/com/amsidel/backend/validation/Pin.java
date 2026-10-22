package com.amsidel.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/** Login PIN rule, see {@link PinPolicy}. Null passes, so pair it with @NotBlank where a PIN is required. */
@Documented
@Constraint(validatedBy = PinValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pin {

    String message() default PinPolicy.RULE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
