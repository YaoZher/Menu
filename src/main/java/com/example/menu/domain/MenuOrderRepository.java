package com.example.menu.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MenuOrderRepository extends JpaRepository<MenuOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from MenuOrder o where o.id = 1")
    Optional<MenuOrder> findForUpdate();
}
