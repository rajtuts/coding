package com.example.dynamicvalidation.rule;

import java.util.ArrayList;
import java.util.List;

public class ValidationGroup {
    private final String validationType;
    private final List<ValidationField> fields = new ArrayList<>();
    private final List<CrossFieldRule> crossFieldRules = new ArrayList<>();

    public ValidationGroup(String validationType) {
        this.validationType = validationType;
    }

    public String getValidationType() { return validationType; }
    public List<ValidationField> getFields() { return fields; }
    public List<CrossFieldRule> getCrossFieldRules() { return crossFieldRules; }
}
