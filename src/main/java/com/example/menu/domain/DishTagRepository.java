package com.example.menu.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishTagRepository extends JpaRepository<DishTag, Long> {
    List<DishTag> findByCategoryIdOrderByIdAsc(Long categoryId);
    Optional<DishTag> findByCategoryIdAndNameIgnoreCase(Long categoryId, String name);
}
