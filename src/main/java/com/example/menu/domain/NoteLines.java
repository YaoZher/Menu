package com.example.menu.domain;

import java.util.ArrayList;
import java.util.List;

public final class NoteLines {
    private NoteLines() { }

    public static List<NoteLine> parse(String notes) {
        if (notes == null || notes.isBlank()) return List.of();
        List<NoteLine> result = new ArrayList<>();
        for (String rawLine : notes.split("\\R", -1)) {
            String text = rawLine.strip();
            NoteLine.Type type = NoteLine.Type.NOTE;
            if (text.startsWith("•") || text.startsWith("·")) {
                type = NoteLine.Type.INGREDIENT;
                text = text.substring(1).strip();
            } else if (text.startsWith("//")) {
                type = NoteLine.Type.STEP;
                text = text.substring(2).strip();
            }
            if (!text.isEmpty()) result.add(new NoteLine(type, text));
        }
        return List.copyOf(result);
    }
}
