package com.example.dynamicvalidation.controller;

import com.example.dynamicvalidation.engine.DynamicValidationEngine;
import com.example.dynamicvalidation.registry.DtoRegistry;
import com.example.dynamicvalidation.request.DynamicValidationRequest;
import com.example.dynamicvalidation.response.ValidationError;
import com.example.dynamicvalidation.response.ValidationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/validation")
public class ValidationController {

    private final DtoRegistry dtoRegistry;
    private final DynamicValidationEngine validationEngine;
    private final ObjectMapper objectMapper;

    public ValidationController(
            DtoRegistry dtoRegistry,
            DynamicValidationEngine validationEngine,
            ObjectMapper objectMapper) {
        this.dtoRegistry = dtoRegistry;
        this.validationEngine = validationEngine;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ValidationResponse> validate(
            @RequestBody DynamicValidationRequest request) {

        Class<?> dtoClass =
                dtoRegistry.getDtoClass(request.getDtoType());

        Object dto =
                objectMapper.convertValue(
                        request.getData(), dtoClass);

        List<ValidationError> errors =
                validationEngine.validate(
                        dto,
                        request.getValidationType(),
                        request.getAdditionalFields());

        return ResponseEntity.ok(
                new ValidationResponse(
                        errors.isEmpty(),
                        request.getDtoType(),
                        request.getValidationType(),
                        errors));
    }
}
