package com.example.dynamicvalidation.rule;

import java.util.List;

public record ValidationField(
        String fieldName,
        List<String> validators
) {}
