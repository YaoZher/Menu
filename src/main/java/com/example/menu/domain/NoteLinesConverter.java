package com.example.menu.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter // 注释：@Converter 表示该类是一个 JPA 属性转换器，用于将实体类的属性值转换为数据库列的值，反之亦然。
public class NoteLinesConverter implements AttributeConverter<List<NoteLine>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<NoteLine>> TYPE = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<NoteLine> value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot persist structured notes", exception);
        }
    }

    @Override
    public List<NoteLine> convertToEntityAttribute(String value) {
        // Null must stay null so an absent embedded submission snapshot stays absent.
        if (value == null) return null;
        try { return List.copyOf(MAPPER.readValue(value, TYPE)); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot read structured notes", exception);
        }
    }
}
