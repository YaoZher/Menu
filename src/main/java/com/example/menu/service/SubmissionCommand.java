package com.example.menu.service;

import org.springframework.web.multipart.MultipartFile;

public record SubmissionCommand(String operation, String dishId, String expectedVersion, String name,
                                String videoUrl, String notes, String imageAction, MultipartFile image) { }
