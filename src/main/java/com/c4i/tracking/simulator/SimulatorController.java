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
    private static final int MAX_SWARM_COUNT = 200;
    private static final int MAX_SWARM_DURATION_SEC = 300;

    private final DroneSimulator droneSimulator;
    private final DmzSwarmSimulator dmzSwarmSimulator;

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

    // DMZ 북단에서 남하하는 드론 스웜 시나리오 -- 상시 생성기가 아니라 durationSec
    // 동안만 돌고 자동으로 멈춘다. 이미 진행 중이면 409로 거부(중복 실행 방지).
    @PostMapping("/swarm")
    public ResponseEntity<String> swarm(
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(defaultValue = "90") int durationSec) {
        if (count < 1 || count > MAX_SWARM_COUNT) {
            return ResponseEntity.badRequest()
                    .body("count must be between 1 and " + MAX_SWARM_COUNT);
        }
        if (durationSec < 1 || durationSec > MAX_SWARM_DURATION_SEC) {
            return ResponseEntity.badRequest()
                    .body("durationSec must be between 1 and " + MAX_SWARM_DURATION_SEC);
        }
        boolean started = dmzSwarmSimulator.start(count, durationSec);
        if (!started) {
            return ResponseEntity.status(409).body("이미 진행 중인 스웜 시나리오가 있습니다");
        }
        return ResponseEntity.ok("DMZ 드론 스웜 시나리오 시작: 드론 %d대, %d초간 진행".formatted(count, durationSec));
    }
}
