package com.example.menu.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface StoredImageRepository extends JpaRepository<StoredImage, UUID> { }
