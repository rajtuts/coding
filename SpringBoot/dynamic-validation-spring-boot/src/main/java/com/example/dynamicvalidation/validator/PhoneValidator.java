package com.example.dynamicvalidation.validator;

import org.springframework.stereotype.Component;

@Component
public class PhoneValidator implements FieldValidator {
    private static final String PHONE = "^[6-9][0-9]{9}$";

    @Override
    public String getName() { return "phone"; }

    @Override
    public boolean supports(Class<?> fieldType) {
        return fieldType == String.class || fieldType == Object.class;
    }

    @Override
    public boolean isValid(Object value) {
        if (value == null || value.toString().isBlank()) return true;
        return value.toString().matches(PHONE);
    }

    @Override
    public String getMessage() { return "Invalid phone number"; }
}
