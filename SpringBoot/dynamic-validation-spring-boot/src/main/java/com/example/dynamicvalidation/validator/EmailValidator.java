package com.example.dynamicvalidation.validator;

import org.springframework.stereotype.Component;

@Component
public class EmailValidator implements FieldValidator {
    private static final String EMAIL =
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";

    @Override
    public String getName() { return "email"; }

    @Override
    public boolean supports(Class<?> fieldType) {
        return fieldType == String.class || fieldType == Object.class;
    }

    @Override
    public boolean isValid(Object value) {
        if (value == null || value.toString().isBlank()) return true;
        return value.toString().matches(EMAIL);
    }

    @Override
    public String getMessage() { return "Invalid email address"; }
}
