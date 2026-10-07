package com.example.dynamicvalidation.controller;

import com.example.dynamicvalidation.config.DynamicFieldConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/validation-config")
public class AdminValidationController {

    private final DynamicFieldConfiguration configuration;

    public AdminValidationController(
            DynamicFieldConfiguration configuration) {
        this.configuration = configuration;
    }

    @PostMapping("/fields")
    public ResponseEntity<Map<String, Object>> addField(
            @RequestParam String validationType,
            @RequestParam String fieldName,
            @RequestParam List<String> validators) {

        configuration.addField(
                validationType,
                fieldName,
                validators);

        return ResponseEntity.ok(Map.of(
                "message", "Field added successfully",
                "validationType", validationType,
                "fieldName", fieldName,
                "validators", validators
        ));
    }
}
