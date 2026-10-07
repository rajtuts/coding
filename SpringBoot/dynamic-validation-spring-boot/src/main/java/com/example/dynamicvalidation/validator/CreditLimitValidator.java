package com.example.dynamicvalidation.validator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class CreditLimitValidator implements FieldValidator {
    @Override
    public String getName() { return "creditLimit"; }

    @Override
    public boolean supports(Class<?> fieldType) {
        return BigDecimal.class.isAssignableFrom(fieldType);
    }

    @Override
    public boolean isValid(Object value) {
        if (value == null) return true;
        return ((BigDecimal) value).compareTo(BigDecimal.ZERO) >= 0;
    }

    @Override
    public String getMessage() { return "Credit limit must be zero or greater"; }
}
