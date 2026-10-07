package com.example.dynamicvalidation.validator;

import com.example.dynamicvalidation.annotation.ValidPhone;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidPhoneValidator
        implements ConstraintValidator<ValidPhone, String> {

    private static final String PHONE = "^[6-9][0-9]{9}$";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || value.matches(PHONE);
    }
}
