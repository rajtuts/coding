package com.example.dynamicvalidation.registry;

import com.example.dynamicvalidation.dto.CustomerDto;
import com.example.dynamicvalidation.dto.UserDto;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DtoRegistry {
    private final Map<String, Class<?>> registry = Map.of(
            "USER", UserDto.class,
            "CUSTOMER", CustomerDto.class
    );

    public Class<?> getDtoClass(String type) {
        if (type == null) {
            throw new IllegalArgumentException("dtoType is required");
        }

        Class<?> dtoClass = registry.get(type.toUpperCase());
        if (dtoClass == null) {
            throw new IllegalArgumentException("Unknown dtoType: " + type);
        }
        return dtoClass;
    }
}
