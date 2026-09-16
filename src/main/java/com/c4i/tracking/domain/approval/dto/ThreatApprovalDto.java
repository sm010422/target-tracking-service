package com.c4i.tracking.domain.approval.dto;

import com.c4i.tracking.domain.approval.entity.ThreatApproval;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class ThreatApprovalDto {

    @Getter
    @Builder
    public static class Response {
        private Long id;
        private String targetId;
        private String targetType;
        private String threatLevel;
        private String sitrep;
        private String status;
        private LocalDateTime requestedAt;
        private LocalDateTime decidedAt;
        private String decidedBy;
        private String decisionReason;

        public static Response from(ThreatApproval approval) {
            return Response.builder()
                .id(approval.getId())
                .targetId(approval.getTargetId())
                .targetType(approval.getTargetType())
                .threatLevel(approval.getThreatLevel())
                .sitrep(approval.getSitrep())
                .status(approval.getStatus())
                .requestedAt(approval.getRequestedAt())
                .decidedAt(approval.getDecidedAt())
                .decidedBy(approval.getDecidedBy())
                .decisionReason(approval.getDecisionReason())
                .build();
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        private String decision;   // APPROVED | REJECTED
        private String decidedBy;
        private String reason;
    }
}
