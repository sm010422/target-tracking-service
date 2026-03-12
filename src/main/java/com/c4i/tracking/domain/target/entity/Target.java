package com.c4i.tracking.domain.target.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "targets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Target {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String targetId;       // 드론 식별자 ex) DRONE-001

    @Column(nullable = false)
    private String targetType;     // DRONE, AIRCRAFT, MISSILE

    @Column(nullable = false)
    private Double latitude;       // 위도

    @Column(nullable = false)
    private Double longitude;      // 경도

    @Column(nullable = false)
    private Double altitude;       // 고도 (m)

    @Column(nullable = false)
    private Double speed;          // 속도 (km/h)

    @Column(nullable = false)
    private String status;         // DETECTED, TRACKING, LOST

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime detectedAt;

    @Builder
    public Target(String targetId, String targetType, Double latitude,
                  Double longitude, Double altitude, Double speed, String status) {
        this.targetId = targetId;
        this.targetType = targetType;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speed = speed;
        this.status = status;
    }
}
