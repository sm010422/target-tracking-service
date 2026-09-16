package com.c4i.tracking.domain.approval.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI가 HIGH/CRITICAL로 판정한 표적에 대해 사람이 승인/반려하는 의사결정 루프.
 * SITREP을 보여주는 데서 끝나지 않고, 그 판단이 실제 조치(승인/반려)로 이어지고
 * 그 결정이 기록으로 남는 것 자체가 이 도메인의 목적이다.
 */
@Entity
@Table(name = "threat_approvals", indexes = {
    @Index(name = "idx_threat_approvals_status", columnList = "status"),
    @Index(name = "idx_threat_approvals_target_id", columnList = "targetId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ThreatApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String targetId;

    @Column(nullable = false)
    private String targetType;

    @Column(nullable = false)
    private String threatLevel;    // HIGH, CRITICAL (승인이 필요한 등급만 생성됨)

    @Lob
    @Column(nullable = false)
    private String sitrep;         // 요청 시점의 SITREP 스냅샷

    @Column(nullable = false)
    private String status;         // PENDING, APPROVED, REJECTED

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;

    private String decidedBy;

    @Lob
    private String decisionReason;

    @Builder
    public ThreatApproval(String targetId, String targetType, String threatLevel, String sitrep) {
        this.targetId = targetId;
        this.targetType = targetType;
        this.threatLevel = threatLevel;
        this.sitrep = sitrep;
        this.status = "PENDING";
        this.requestedAt = LocalDateTime.now();
    }

    public void decide(String status, String decidedBy, String reason) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionReason = reason;
        this.decidedAt = LocalDateTime.now();
    }
}
