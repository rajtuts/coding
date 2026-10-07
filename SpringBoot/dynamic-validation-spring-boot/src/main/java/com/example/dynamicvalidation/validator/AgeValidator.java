package com.example.dynamicvalidation.validator;

import org.springframework.stereotype.Component;

@Component
public class AgeValidator implements FieldValidator {
    @Override
    public String getName() { return "adultAge"; }

    @Override
    public boolean supports(Class<?> fieldType) {
        return fieldType == Integer.class || fieldType == int.class;
    }

    @Override
    public boolean isValid(Object value) {
        if (value == null) return true;
        return ((Number) value).intValue() >= 18;
    }

    @Override
    public String getMessage() { return "Age must be at least 18"; }
}
