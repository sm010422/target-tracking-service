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

    // AssetRecommendationService가 계산한 상위 3개 요격 자산 추천을 JSON 배열로
    // 스냅샷 저장한다 (List<AssetRecommendation> 직렬화). 자산 카탈로그가 나중에
    // 바뀌어도 과거 승인 기록이 "그 당시 뭘 추천했었는지" 그대로 남도록 하기 위함.
    @Lob
    private String recommendedOptionsJson;

    // decide()에서 APPROVED일 때 recommendedOptionsJson 중 실제로 고른 자산명.
    // MSS의 "3~4개 옵션 중 하나 클릭"에 대응 -- REJECTED면 null.
    private String selectedOption;

    @Column(nullable = false)
    private String status;         // PENDING, APPROVED, REJECTED

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;

    private String decidedBy;

    @Lob
    private String decisionReason;

    @Builder
    public ThreatApproval(String targetId, String targetType, String threatLevel, String sitrep,
                           String recommendedOptionsJson) {
        this.targetId = targetId;
        this.targetType = targetType;
        this.threatLevel = threatLevel;
        this.sitrep = sitrep;
        this.recommendedOptionsJson = recommendedOptionsJson;
        this.status = "PENDING";
        this.requestedAt = LocalDateTime.now();
    }

    public void decide(String status, String decidedBy, String reason, String selectedOption) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionReason = reason;
        this.selectedOption = selectedOption;
        this.decidedAt = LocalDateTime.now();
    }
}
