package com.example.menu.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DishRepository extends JpaRepository<Dish, Long> {
    List<Dish> findAllByOrderByUpdatedAtDescIdDesc();
    boolean existsByImageId(UUID imageId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dish d where d.id = :id")
    Optional<Dish> findForUpdate(@Param("id") Long id);
}
