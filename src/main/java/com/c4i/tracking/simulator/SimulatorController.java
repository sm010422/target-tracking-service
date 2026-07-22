package com.c4i.tracking.simulator;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private static final int MAX_ROUNDS = 50;

    private final DroneSimulator droneSimulator;

    // 호출 시에만 rounds회 만큼 드론 위치 데이터를 생성 (기본 1회, 최대 50회)
    @PostMapping("/run")
    public ResponseEntity<String> run(@RequestParam(defaultValue = "1") int rounds) {
        if (rounds < 1 || rounds > MAX_ROUNDS) {
            return ResponseEntity.badRequest()
                    .body("rounds must be between 1 and " + MAX_ROUNDS);
        }
        droneSimulator.simulate(rounds);
        return ResponseEntity.ok(rounds + "회 시뮬레이션 실행 완료");
    }
}
