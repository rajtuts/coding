package com.example.dynamicvalidation.validator;

public interface CrossFieldValidator {
    String getName();
    boolean isValid(Object dto, String field1, String field2);
    String getMessage();
}
