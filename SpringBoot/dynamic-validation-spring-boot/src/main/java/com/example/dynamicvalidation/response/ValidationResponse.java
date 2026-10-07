package com.example.dynamicvalidation.response;

import java.util.List;

public record ValidationResponse(
        boolean valid,
        String dtoType,
        String validationType,
        List<ValidationError> errors
) {}
