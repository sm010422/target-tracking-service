package com.c4i.tracking.domain.target.dto;

import lombok.Builder;
import lombok.Getter;

public class TargetDto {

    @Getter
    @Builder
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
        private Long id;
        private String targetId;
        private String targetType;
        private Double latitude;
        private Double longitude;
        private Double altitude;
        private Double speed;
        private String status;
        private String detectedAt;
    }
}
