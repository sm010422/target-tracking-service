package com.c4i.tracking.domain.approval.dto;

import com.c4i.tracking.domain.approval.entity.ThreatApproval;
import com.c4i.tracking.domain.asset.AssetRecommendation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class ThreatApprovalDto {

    @Getter
    @Builder
    public static class Response {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        private Long id;
        private String targetId;
        private String targetType;
        private String threatLevel;
        private String sitrep;
        private List<AssetRecommendation> recommendedOptions;
        private String selectedOption;
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
                .recommendedOptions(parseOptions(approval.getRecommendedOptionsJson()))
                .selectedOption(approval.getSelectedOption())
                .status(approval.getStatus())
                .requestedAt(approval.getRequestedAt())
                .decidedAt(approval.getDecidedAt())
                .decidedBy(approval.getDecidedBy())
                .decisionReason(approval.getDecisionReason())
                .build();
        }

        private static List<AssetRecommendation> parseOptions(String json) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return OBJECT_MAPPER.readerForListOf(AssetRecommendation.class).readValue(json);
            } catch (JsonProcessingException e) {
                return List.of();
            }
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        private String decision;   // APPROVED | REJECTED
        private String decidedBy;
        private String reason;
        private String selectedOption; // APPROVED일 때 recommendedOptions 중 고른 assetName
    }
}
