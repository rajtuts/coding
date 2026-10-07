package com.example.dynamicvalidation.engine;

import com.example.dynamicvalidation.registry.ValidationTypeRegistry;
import com.example.dynamicvalidation.response.ValidationError;
import com.example.dynamicvalidation.rule.CrossFieldRule;
import com.example.dynamicvalidation.rule.ValidationField;
import com.example.dynamicvalidation.rule.ValidationGroup;
import com.example.dynamicvalidation.validator.CrossFieldValidator;
import com.example.dynamicvalidation.validator.FieldValidator;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DynamicValidationEngine {

    private final ValidationTypeRegistry registry;
    private final Map<String, FieldValidator> fieldValidators;
    private final Map<String, CrossFieldValidator> crossFieldValidators;

    public DynamicValidationEngine(
            ValidationTypeRegistry registry,
            List<FieldValidator> fieldValidators,
            List<CrossFieldValidator> crossFieldValidators) {

        this.registry = registry;
        this.fieldValidators = fieldValidators.stream()
                .collect(Collectors.toMap(
                        v -> v.getName().toLowerCase(),
                        Function.identity()));

        this.crossFieldValidators = crossFieldValidators.stream()
                .collect(Collectors.toMap(
                        v -> v.getName().toLowerCase(),
                        Function.identity()));
    }

    public List<ValidationError> validate(
            Object dto,
            String validationType,
            Map<String, String> additionalFields) {

        ValidationGroup group = registry.get(validationType);

        List<ValidationField> fields =
                new ArrayList<>(group.getFields());

        // Dynamic fields based on request input.
        addDynamicFields(fields, additionalFields);

        List<ValidationError> errors = new ArrayList<>();
        BeanWrapperImpl wrapper = new BeanWrapperImpl(dto);

        for (ValidationField field : fields) {

            if (!wrapper.isReadableProperty(field.fieldName())) {
                errors.add(new ValidationError(
                        field.fieldName(),
                        "Unknown field: " + field.fieldName()));
                continue;
            }

            Object value = wrapper.getPropertyValue(field.fieldName());
            Class<?> fieldType = value != null
                    ? value.getClass()
                    : wrapper.getPropertyType(field.fieldName());

            for (String validatorName : field.validators()) {

                FieldValidator validator =
                        fieldValidators.get(
                                validatorName.toLowerCase());

                if (validator == null) {
                    errors.add(new ValidationError(
                            field.fieldName(),
                            "Validator not found: " + validatorName));
                    continue;
                }

                if (!validator.supports(fieldType)) {
                    errors.add(new ValidationError(
                            field.fieldName(),
                            "Validator '" + validatorName +
                                    "' does not support field type"));
                    continue;
                }

                if (!validator.isValid(value)) {
                    errors.add(new ValidationError(
                            field.fieldName(),
                            validator.getMessage()));
                }
            }
        }

        for (CrossFieldRule rule : group.getCrossFieldRules()) {

            CrossFieldValidator validator =
                    crossFieldValidators.get(
                            rule.validator().toLowerCase());

            if (validator == null) {
                errors.add(new ValidationError(
                        "_global",
                        "Cross-field validator not found: "
                                + rule.validator()));
                continue;
            }

            if (!validator.isValid(
                    dto, rule.field1(), rule.field2())) {

                errors.add(new ValidationError(
                        rule.field2(),
                        rule.message()));
            }
        }

        return errors;
    }

    private void addDynamicFields(
            List<ValidationField> fields,
            Map<String, String> additionalFields) {

        if (additionalFields == null || additionalFields.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry :
                additionalFields.entrySet()) {

            String fieldName = entry.getKey();
            String validatorName = entry.getValue();

            boolean exists = fields.stream()
                    .anyMatch(f -> f.fieldName().equals(fieldName));

            if (!exists) {
                fields.add(new ValidationField(
                        fieldName,
                        List.of(validatorName)));
            }
        }
    }
}
