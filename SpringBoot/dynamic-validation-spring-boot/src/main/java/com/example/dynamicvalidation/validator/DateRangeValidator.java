package com.example.dynamicvalidation.validator;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class DateRangeValidator implements CrossFieldValidator {
    @Override
    public String getName() { return "dateRange"; }

    @Override
    public boolean isValid(Object dto, String field1, String field2) {
        BeanWrapperImpl wrapper = new BeanWrapperImpl(dto);
        Object first = wrapper.getPropertyValue(field1);
        Object second = wrapper.getPropertyValue(field2);

        if (first == null || second == null) return true;

        return !((LocalDate) first).isAfter((LocalDate) second);
    }

    @Override
    public String getMessage() {
        return "Start date must be before or equal to end date";
    }
}
