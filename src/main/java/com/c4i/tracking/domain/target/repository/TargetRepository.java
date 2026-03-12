package com.c4i.tracking.domain.target.repository;

import com.c4i.tracking.domain.target.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TargetRepository extends JpaRepository<Target, Long> {
    List<Target> findByTargetId(String targetId);
    List<Target> findByStatus(String status);
}
