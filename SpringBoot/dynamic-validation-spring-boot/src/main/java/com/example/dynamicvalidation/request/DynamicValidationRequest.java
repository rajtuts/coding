package com.example.dynamicvalidation.request;

import java.util.Map;

public class DynamicValidationRequest {
    private String dtoType;
    private String validationType;
    private String validationMode;
    private Map<String, Object> data;
    private Map<String, String> additionalFields;

    public String getDtoType() { return dtoType; }
    public void setDtoType(String dtoType) { this.dtoType = dtoType; }

    public String getValidationType() { return validationType; }
    public void setValidationType(String validationType) { this.validationType = validationType; }

    public String getValidationMode() { return validationMode; }
    public void setValidationMode(String validationMode) { this.validationMode = validationMode; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public Map<String, String> getAdditionalFields() { return additionalFields; }
    public void setAdditionalFields(Map<String, String> additionalFields) {
        this.additionalFields = additionalFields;
    }
}
