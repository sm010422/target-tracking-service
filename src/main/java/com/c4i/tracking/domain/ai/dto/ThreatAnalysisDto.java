package com.c4i.tracking.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public class ThreatAnalysisDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String targetId;
        private String targetType;
        private Double latitude;
        private Double longitude;
        private Double altitude;
        private Double speed;
        private String status;
    }

    @Getter
    @Builder
    public static class Response {
        private String targetId;
        private String targetType;
        private String threatLevel;       // LOW / MEDIUM / HIGH / CRITICAL
        private String sitrep;            // AI 생성 상황보고서
        private List<String> similarPatterns; // 유사 위협 패턴
        private boolean aiEnabled;
    }
}
