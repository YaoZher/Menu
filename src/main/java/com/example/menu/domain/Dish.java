package com.example.menu.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dishes")
public class Dish {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80)
    private String name;
    private UUID imageId;
    @Column(length = 2048)
    private String videoUrl;
    @Column(length = 2000)
    private String notes;
    @Version
    private long version;
    @Column(nullable = false)
    private Instant updatedAt;

    protected Dish() { }

    public Dish(DishSnapshot snapshot, Instant now) { apply(snapshot, now); }

    public void apply(DishSnapshot snapshot, Instant now) {
        name = snapshot.getName();
        imageId = snapshot.getImageId();
        videoUrl = snapshot.getVideoUrl();
        notes = snapshot.getNotes();
        updatedAt = now;
    }

    public DishSnapshot snapshot() { return new DishSnapshot(name, imageId, videoUrl, notes); }
    public Long getId() { return id; }
    public String getName() { return name; }
    public UUID getImageId() { return imageId; }
    public String getVideoUrl() { return videoUrl; }
    public String getNotes() { return notes; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
