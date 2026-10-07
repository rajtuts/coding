package com.example.dynamicvalidation.registry;

import com.example.dynamicvalidation.rule.ValidationGroup;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ValidationTypeRegistry {
    private final Map<String, ValidationGroup> groups = new HashMap<>();

    public void register(ValidationGroup group) {
        groups.put(group.getValidationType().toUpperCase(), group);
    }

    public ValidationGroup get(String type) {
        if (type == null) {
            throw new IllegalArgumentException("validationType is required");
        }

        ValidationGroup group = groups.get(type.toUpperCase());
        if (group == null) {
            throw new IllegalArgumentException(
                    "Unknown validationType: " + type);
        }
        return group;
    }
}
