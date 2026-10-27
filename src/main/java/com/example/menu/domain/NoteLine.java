package com.example.menu.domain;

import java.util.Objects;

public record NoteLine(Type type, String text) {
    public NoteLine {
        Objects.requireNonNull(type, "Note line type is required");
        Objects.requireNonNull(text, "Note line text is required");
    }

    public enum Type { INGREDIENT, STEP, NOTE }
}
