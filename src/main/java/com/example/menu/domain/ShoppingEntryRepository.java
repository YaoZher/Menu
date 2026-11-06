package com.example.menu.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShoppingEntryRepository extends JpaRepository<ShoppingEntry, UUID> {
    List<ShoppingEntry> findByOwnerOrderByAddedAtAscIdAsc(String owner);
    List<ShoppingEntry> findByDishIdOrderByIdAsc(Long dishId);
    Optional<ShoppingEntry> findByOwnerAndDishId(String owner, Long dishId);
    Optional<ShoppingEntry> findByIdAndOwner(UUID id, String owner);

    // 只获取父级 ID，然后再对其加锁，
    // 不要在持久化上下文中缓存过时的条目。
    @Query("select e.dish.id from ShoppingEntry e where e.id = :id and e.owner = :owner")
    Optional<Long> findDishId(@Param("id") UUID id, @Param("owner") String owner);
}
