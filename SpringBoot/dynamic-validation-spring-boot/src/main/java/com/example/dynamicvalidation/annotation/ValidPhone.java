package com.example.dynamicvalidation.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import com.example.dynamicvalidation.validator.ValidPhoneValidator;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = ValidPhoneValidator.class)
public @interface ValidPhone {
    String message() default "Invalid phone number";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
