package com.example.menu.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "dishes")
public class Dish {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) // 注释：@GeneratedValue 用于自动生成主键值，strategy = GenerationType.IDENTITY 表示使用数据库的自增列来生成主键值。每次插入新记录时，数据库会自动为该字段分配一个唯一的值。
    private Long id;
    @Column(nullable = false, length = 80)
    private String name;
    private UUID imageId;
    @Column(length = 2048)
    private String videoUrl;
    @Column(length = 2000)
    private String notes;
    @Lob // 注释: @Lob 表示该字段将被映射为数据库中的大对象（Large Object），
         // 通常用于存储大量文本或二进制数据。
         // 在不同的数据库中，@Lob 可以映射为 CLOB（Character Large Object）或 BLOB（Binary Large Object）。
         // 在这个上下文中，它用于存储菜品的详细笔记信息。
    @Convert(converter = NoteLinesConverter.class)
    @Column(name = "note_lines")
    private List<NoteLine> noteLines;
    @Lob
    @Convert(converter = TagRefsConverter.class)
    private List<TagRef> tags;
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
        noteLines = List.copyOf(snapshot.getNoteLines());
        tags = List.copyOf(snapshot.getTags());
        updatedAt = now;
    }

    public DishSnapshot snapshot() { return new DishSnapshot(name, imageId, videoUrl, notes, getNoteLines(), getTags()); }
    public Long getId() { return id; }
    public String getName() { return name; }
    public UUID getImageId() { return imageId; }
    public String getVideoUrl() { return videoUrl; }
    public String getNotes() { return notes; }
    public List<NoteLine> getNoteLines() { return noteLines == null ? NoteLines.parse(notes) : noteLines; }
    public List<TagRef> getTags() { return tags == null ? List.of() : tags; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
