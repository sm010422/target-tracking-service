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
    // 실제 진행방향(도, 0=북/시계방향). ADS-B의 track 필드 -- 가짜 드론 시뮬레이터는 안 채우므로 null 가능.
    private Double heading;
}
