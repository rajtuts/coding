package com.example.dynamicvalidation.validator;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

@Component
public class PasswordMatchValidator implements CrossFieldValidator {
    @Override
    public String getName() { return "passwordMatch"; }

    @Override
    public boolean isValid(Object dto, String field1, String field2) {
        BeanWrapperImpl wrapper = new BeanWrapperImpl(dto);
        Object first = wrapper.getPropertyValue(field1);
        Object second = wrapper.getPropertyValue(field2);

        if (first == null && second == null) return true;
        return first != null && first.equals(second);
    }

    @Override
    public String getMessage() {
        return "Password and confirmPassword must match";
    }
}
