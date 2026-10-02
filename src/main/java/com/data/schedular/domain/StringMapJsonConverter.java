package com.data.schedular.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stores a {@code Map<String, String>} as a JSON object in a text column. */
@Converter
public class StringMapJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        return attribute == null || attribute.isEmpty() ? null : MAPPER.writeValueAsString(attribute);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank() ? new LinkedHashMap<>() : MAPPER.readValue(dbData, TYPE);
    }
}
