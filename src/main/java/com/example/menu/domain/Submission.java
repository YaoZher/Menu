package com.example.menu.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "submissions", indexes = @Index(name = "idx_submission_status_created", columnList = "status,created_at"))
public class Submission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ChangeOperation operation;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private SubmissionStatus status = SubmissionStatus.PENDING;
    private Long dishId;
    private Long baseVersion;
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "name", column = @Column(name = "before_name", length = 80)),
        @AttributeOverride(name = "imageId", column = @Column(name = "before_image_id")),
        @AttributeOverride(name = "videoUrl", column = @Column(name = "before_video_url", length = 2048)),
        @AttributeOverride(name = "notes", column = @Column(name = "before_notes", length = 2000)),
        @AttributeOverride(name = "noteLines", column = @Column(name = "before_note_lines"))
    })
    private DishSnapshot beforeSnapshot;
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "name", column = @Column(name = "after_name", length = 80)),
        @AttributeOverride(name = "imageId", column = @Column(name = "after_image_id")),
        @AttributeOverride(name = "videoUrl", column = @Column(name = "after_video_url", length = 2048)),
        @AttributeOverride(name = "notes", column = @Column(name = "after_notes", length = 2000)),
        @AttributeOverride(name = "noteLines", column = @Column(name = "after_note_lines"))
    })
    private DishSnapshot afterSnapshot;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    private Instant reviewedAt;
    @Column(length = 255)
    private String reviewedBy;
    @Column(length = 1000)
    private String reviewNote;

    protected Submission() { }

    public Submission(ChangeOperation operation, Long dishId, Long baseVersion,
                      DishSnapshot beforeSnapshot, DishSnapshot afterSnapshot, Instant now) {
        this.operation = operation;
        this.dishId = dishId;
        this.baseVersion = baseVersion;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
        this.createdAt = now;
    }

    public void review(SubmissionStatus status, String reviewer, String note, Instant now) {
        this.status = status;
        reviewedBy = reviewer;
        reviewNote = note;
        reviewedAt = now;
    }

    public void setCreatedDishId(Long dishId) { this.dishId = dishId; }
    public Long getId() { return id; }
    public ChangeOperation getOperation() { return operation; }
    public SubmissionStatus getStatus() { return status; }
    public Long getDishId() { return dishId; }
    public Long getBaseVersion() { return baseVersion; }
    public DishSnapshot getBeforeSnapshot() { return beforeSnapshot; }
    public DishSnapshot getAfterSnapshot() { return afterSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public String getReviewNote() { return reviewNote; }
}
