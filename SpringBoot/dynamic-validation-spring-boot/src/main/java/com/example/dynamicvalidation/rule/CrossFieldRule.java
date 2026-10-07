package com.example.dynamicvalidation.rule;

public record CrossFieldRule(
        String validator,
        String field1,
        String field2,
        String message
) {}
