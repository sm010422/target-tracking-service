package com.c4i.tracking.simulator;

import com.c4i.tracking.kafka.TargetEvent;
import com.c4i.tracking.kafka.TargetProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class DroneSimulator {

    private final TargetProducer targetProducer;
    private final Random random = new Random();

    private static final List<String> DRONE_IDS = List.of(
            "DRONE-001", "DRONE-002", "DRONE-003"
    );

    @Scheduled(fixedRate = 1000) // 1초마다 실행
    public void simulate() {
        DRONE_IDS.forEach(droneId -> {
            TargetEvent event = TargetEvent.builder()
                    .targetId(droneId)
                    .targetType("DRONE")
                    // 한반도 위도 범위
                    .latitude(34.0 + random.nextDouble() * 4.0)
                    // 한반도 경도 범위
                    .longitude(126.0 + random.nextDouble() * 4.0)
                    .altitude(100.0 + random.nextDouble() * 900.0)
                    .speed(50.0 + random.nextDouble() * 200.0)
                    .status("DETECTED")
                    .build();

            targetProducer.send(event);
        });
    }
}
