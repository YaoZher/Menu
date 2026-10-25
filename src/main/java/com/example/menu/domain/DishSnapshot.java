package com.example.menu.domain;

import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public class DishSnapshot {
    private String name;
    private UUID imageId;
    private String videoUrl;
    private String notes;

    protected DishSnapshot() { }

    public DishSnapshot(String name, UUID imageId, String videoUrl, String notes) {
        this.name = name;
        this.imageId = imageId;
        this.videoUrl = videoUrl;
        this.notes = notes;
    }

    public String getName() { return name; }
    public UUID getImageId() { return imageId; }
    public String getVideoUrl() { return videoUrl; }
    public String getNotes() { return notes; }
}
