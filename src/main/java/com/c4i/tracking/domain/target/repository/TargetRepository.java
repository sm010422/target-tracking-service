package com.c4i.tracking.domain.target.repository;

import com.c4i.tracking.domain.target.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TargetRepository extends JpaRepository<Target, Long> {
    List<Target> findByTargetId(String targetId);
    List<Target> findByStatus(String status);

    @Query("SELECT t FROM Target t WHERE t.targetId = :targetId ORDER BY t.detectedAt DESC LIMIT 1")
    Optional<Target> findLatestByTargetId(String targetId);
}
