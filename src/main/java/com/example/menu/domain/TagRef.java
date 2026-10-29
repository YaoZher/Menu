package com.example.menu.domain;

/** Labels are copied into the dish/review snapshot, so audit history remains self-contained. */
public record TagRef(Long id, String name, Long categoryId, String categoryName) { }
