package com.example.menu.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class TagRefsConverter implements AttributeConverter<List<TagRef>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<TagRef>> TYPE = new TypeReference<>() { };

    @Override public String convertToDatabaseColumn(List<TagRef> value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Cannot persist tags", error); }
    }

    @Override public List<TagRef> convertToEntityAttribute(String value) {
        // Returning an empty list here would materialize an absent embedded before/after snapshot.
        if (value == null) return null;
        try { return List.copyOf(MAPPER.readValue(value, TYPE)); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Cannot read tags", error); }
    }
}
