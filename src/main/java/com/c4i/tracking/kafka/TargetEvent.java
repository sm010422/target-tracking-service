package com.c4i.tracking.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetEvent {
    private String targetId;
    private String targetType;
    private Double latitude;
    private Double longitude;
    private Double altitude;
    private Double speed;
    private String status;
}
