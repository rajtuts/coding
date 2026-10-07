package com.example.dynamicvalidation.validator;

import org.springframework.stereotype.Component;

@Component
public class RequiredValidator implements FieldValidator {
    @Override
    public String getName() { return "required"; }

    @Override
    public boolean supports(Class<?> fieldType) { return true; }

    @Override
    public boolean isValid(Object value) {
        if (value == null) return false;
        return !(value instanceof String s) || !s.isBlank();
    }

    @Override
    public String getMessage() { return "Field is required"; }
}
