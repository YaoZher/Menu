package com.example.menu.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    List<Submission> findAllByOrderByCreatedAtDescIdDesc();
    List<Submission> findByStatusOrderByCreatedAtDescIdDesc(SubmissionStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Submission s where s.id = :id")
    Optional<Submission> findForUpdate(@Param("id") Long id);
}
