package com.example.dynamicvalidation.response;

public record ValidationError(
        String field,
        String message
) {}
