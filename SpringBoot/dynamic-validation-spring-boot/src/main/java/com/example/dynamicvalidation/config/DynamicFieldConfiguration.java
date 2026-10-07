package com.example.dynamicvalidation.config;

import com.example.dynamicvalidation.registry.ValidationTypeRegistry;
import com.example.dynamicvalidation.rule.ValidationField;
import com.example.dynamicvalidation.rule.ValidationGroup;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DynamicFieldConfiguration {

    private final ValidationTypeRegistry registry;

    public DynamicFieldConfiguration(ValidationTypeRegistry registry) {
        this.registry = registry;
    }

    public void addField(
            String validationType,
            String fieldName,
            List<String> validators) {

        ValidationGroup group = registry.get(validationType);

        boolean alreadyExists = group.getFields().stream()
                .anyMatch(f -> f.fieldName().equals(fieldName));

        if (!alreadyExists) {
            group.getFields().add(
                    new ValidationField(fieldName, validators));
        }
    }
}
