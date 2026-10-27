package com.example.menu.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Convert;
import jakarta.persistence.Lob;
import java.util.List;
import java.util.UUID;

@Embeddable
public class DishSnapshot {
    private String name;
    private UUID imageId;
    private String videoUrl;
    private String notes;
    @Lob
    @Convert(converter = NoteLinesConverter.class)
    private List<NoteLine> noteLines;

    protected DishSnapshot() { }

    public DishSnapshot(String name, UUID imageId, String videoUrl, String notes) {
        this(name, imageId, videoUrl, notes, NoteLines.parse(notes));
    }

    DishSnapshot(String name, UUID imageId, String videoUrl, String notes, List<NoteLine> noteLines) {
        this.name = name;
        this.imageId = imageId;
        this.videoUrl = videoUrl;
        this.notes = notes;
        this.noteLines = List.copyOf(noteLines);
    }

    public String getName() { return name; }
    public UUID getImageId() { return imageId; }
    public String getVideoUrl() { return videoUrl; }
    public String getNotes() { return notes; }
    public List<NoteLine> getNoteLines() { return noteLines == null ? NoteLines.parse(notes) : noteLines; }
}
