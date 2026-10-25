package com.example.menu.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "stored_images")
public class StoredImage {
    @Id
    private UUID id;
    @Column(nullable = false, length = 32)
    private String contentType;
    @Lob @Column(nullable = false)
    private byte[] content;

    protected StoredImage() { }
    public StoredImage(UUID id, String contentType, byte[] content) {
        this.id = id;
        this.contentType = contentType;
        this.content = content;
    }

    public UUID getId() { return id; }
    public String getContentType() { return contentType; }
    public byte[] getContent() { return content; }
}
