package com.example.dynamicvalidation.config;

import com.example.dynamicvalidation.registry.ValidationTypeRegistry;
import com.example.dynamicvalidation.rule.CrossFieldRule;
import com.example.dynamicvalidation.rule.ValidationGroup;
import com.example.dynamicvalidation.rule.ValidationField;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Configuration
public class ValidationConfiguration {

    private final ValidationTypeRegistry registry;

    public ValidationConfiguration(ValidationTypeRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void configure() {

        ValidationGroup userCreate = new ValidationGroup("USER_CREATE");

        userCreate.getFields().add(new ValidationField(
                "firstName", List.of("required")));
        userCreate.getFields().add(new ValidationField(
                "lastName", List.of("required")));
        userCreate.getFields().add(new ValidationField(
                "email", List.of("required", "email")));
        userCreate.getFields().add(new ValidationField(
                "phone", List.of("required", "phone")));
        userCreate.getFields().add(new ValidationField(
                "password", List.of("required")));
        userCreate.getFields().add(new ValidationField(
                "confirmPassword", List.of("required")));
        userCreate.getFields().add(new ValidationField(
                "age", List.of("adultAge")));

        userCreate.getCrossFieldRules().add(new CrossFieldRule(
                "passwordMatch",
                "password",
                "confirmPassword",
                "Password and confirmPassword must match"));

        registry.register(userCreate);

        ValidationGroup userUpdate = new ValidationGroup("USER_UPDATE");

        userUpdate.getFields().add(new ValidationField(
                "email", List.of("email")));
        userUpdate.getFields().add(new ValidationField(
                "phone", List.of("phone")));
        userUpdate.getFields().add(new ValidationField(
                "age", List.of("adultAge")));

        registry.register(userUpdate);

        ValidationGroup userApprove = new ValidationGroup("USER_APPROVE");

        userApprove.getFields().add(new ValidationField(
                "email", List.of("required", "email")));
        userApprove.getFields().add(new ValidationField(
                "phone", List.of("required", "phone")));

        registry.register(userApprove);

        ValidationGroup customerCreate =
                new ValidationGroup("CUSTOMER_CREATE");

        customerCreate.getFields().add(new ValidationField(
                "customerName", List.of("required")));
        customerCreate.getFields().add(new ValidationField(
                "email", List.of("required", "email")));
        customerCreate.getFields().add(new ValidationField(
                "phone", List.of("required", "phone")));
        customerCreate.getFields().add(new ValidationField(
                "creditLimit", List.of("required", "creditLimit")));
        customerCreate.getFields().add(new ValidationField(
                "startDate", List.of("required")));
        customerCreate.getFields().add(new ValidationField(
                "endDate", List.of("required")));

        customerCreate.getCrossFieldRules().add(new CrossFieldRule(
                "dateRange",
                "startDate",
                "endDate",
                "Start date must be before or equal to end date"));

        registry.register(customerCreate);
    }
}
