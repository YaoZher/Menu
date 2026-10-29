package com.example.menu.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagCategoryRepository extends JpaRepository<TagCategory, Long> {
    List<TagCategory> findAllByOrderByIdAsc();
    Optional<TagCategory> findByNameIgnoreCase(String name);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TagCategory c where c.id = :id")
    Optional<TagCategory> findForUpdate(@Param("id") Long id);
}
