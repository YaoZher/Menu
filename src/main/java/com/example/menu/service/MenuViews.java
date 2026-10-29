package com.example.menu.service;

import com.example.menu.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MenuViews {
    private MenuViews() { }

    public record DishView(Long id, String name, String imageUrl, String videoUrl, String notes, List<NoteLine> noteLines,
                           List<TagRef> tags, long version, Instant updatedAt) {
        public static DishView from(Dish dish) {
            return new DishView(dish.getId(), dish.getName(), MenuViews.imageUrl(dish.getImageId()),
                    dish.getVideoUrl(), dish.getNotes(), dish.getNoteLines(), dish.getTags(), dish.getVersion(), dish.getUpdatedAt());
        }
    }

    public record SnapshotView(String name, String imageUrl, String videoUrl, String notes, List<NoteLine> noteLines, List<TagRef> tags) {
        public static SnapshotView from(DishSnapshot snapshot) {
            return snapshot == null ? null : new SnapshotView(snapshot.getName(), MenuViews.imageUrl(snapshot.getImageId()),
                    snapshot.getVideoUrl(), snapshot.getNotes(), snapshot.getNoteLines(), snapshot.getTags());
        }
    }

    public record SubmissionView(Long id, ChangeOperation operation, SubmissionStatus status, Long dishId,
                                 Long baseVersion, SnapshotView before, SnapshotView after, Instant createdAt,
                                 Instant reviewedAt, String reviewedBy, String reviewNote) {
        public static SubmissionView from(Submission submission) {
            return new SubmissionView(submission.getId(), submission.getOperation(), submission.getStatus(),
                    submission.getDishId(), submission.getBaseVersion(), SnapshotView.from(submission.getBeforeSnapshot()),
                    SnapshotView.from(submission.getAfterSnapshot()), submission.getCreatedAt(),
                    submission.getReviewedAt(), submission.getReviewedBy(), submission.getReviewNote());
        }
    }

    public record SubmissionReceipt(Long id, SubmissionStatus status, Instant createdAt) {
        public static SubmissionReceipt from(Submission submission) {
            return new SubmissionReceipt(submission.getId(), submission.getStatus(), submission.getCreatedAt());
        }
    }

    private static String imageUrl(UUID id) { return id == null ? null : "/media/" + id; }
}
