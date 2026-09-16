package com.c4i.tracking.domain.approval.repository;

import com.c4i.tracking.domain.approval.entity.ThreatApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ThreatApprovalRepository extends JpaRepository<ThreatApproval, Long> {

    List<ThreatApproval> findByStatusOrderByRequestedAtDesc(String status);

    List<ThreatApproval> findAllByOrderByRequestedAtDesc();

    // 같은 표적에 대해 쿨다운 내 반복 분석으로 승인 요청이 중복 생성되는 걸 막기 위한 조회.
    Optional<ThreatApproval> findFirstByTargetIdAndStatusOrderByRequestedAtDesc(String targetId, String status);
}
