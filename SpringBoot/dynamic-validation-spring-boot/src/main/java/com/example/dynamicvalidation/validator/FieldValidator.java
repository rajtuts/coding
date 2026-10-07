package com.example.dynamicvalidation.validator;

public interface FieldValidator {
    String getName();
    boolean supports(Class<?> fieldType);
    boolean isValid(Object value);
    String getMessage();
}
