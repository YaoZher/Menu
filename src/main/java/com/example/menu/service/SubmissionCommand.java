package com.example.menu.service;

import org.springframework.web.multipart.MultipartFile;

public record SubmissionCommand(String operation, String dishId, String expectedVersion, String name,
                                String videoUrl, String notes, String imageAction, MultipartFile image, String tagIds) {
    public SubmissionCommand(String operation, String dishId, String expectedVersion, String name,
                             String videoUrl, String notes, String imageAction, MultipartFile image) {
        this(operation, dishId, expectedVersion, name, videoUrl, notes, imageAction, image, null);
    }
}
