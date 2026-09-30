package com.c4i.tracking.simulator;

import com.c4i.tracking.kafka.TargetEvent;
import com.c4i.tracking.kafka.TargetProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DMZ 북단(철원-연천 축선 인근 -- 한국전쟁 때부터 대표적인 남침 통로로 꼽히는 지형,
 * 정확한 군사분계선 좌표가 아니라 상징적인 지역 선택)에서 스폰해서 남쪽으로 실제
 * 전진하는 드론 스웜 침투 시나리오.
 *
 * DroneSimulator(기존 시뮬레이션 버튼)는 매 라운드 좌표를 완전 랜덤으로 다시 뽑아서
 * 순간이동하는 구조였다 -- 09-swarm-load-generator-design-discussion.md에서 지적한
 * 문제. 이 클래스는 드론마다 상태(위경도·방위각·속도)를 유지하면서 매 틱(2초)마다
 * 그 방향으로 haversine 도착점 공식(destination point formula -- 거리 공식을
 * 뒤집어서 "현재 위치 + 방위각 + 거리"로 다음 위치를 구하는 것)으로 실제 전진시킨다.
 *
 * "상시 생성기"가 아니라 start()를 호출하면 정해진 시간(durationSec) 동안만 돌고
 * 자동으로 멈춘다 -- 실제 C4I 워게임 시나리오 재생에 가깝게, 계속 켜놓는 배경
 * 프로세스가 아니라 버튼을 눌러야 시작되는 유한한 이벤트로 설계했다.
 *
 * targetType="DRONE"으로 발행하므로 ThreatAnalysisService.warrantsAiAnalysis()가
 * 무조건 분석 대상으로 잡는다. 다만 드론별 targetId가 시나리오 내내 고정되므로,
 * 기존 10분 쿨다운 덕에 실제 Gemini 호출은 틱 수와 무관하게 "드론 수"만큼만 일어난다
 * (예: 20대 × 90초 시나리오 = 약 20회 호출, 900회가 아니다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DmzSwarmSimulator {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final long TICK_INTERVAL_SEC = 2;

    private static final double SPAWN_LAT_MIN = 38.20;
    private static final double SPAWN_LAT_MAX = 38.30;
    private static final double SPAWN_LON_MIN = 127.05;
    private static final double SPAWN_LON_MAX = 127.35;

    // 정남향(180도) 기준 좌우로 흩어지게 -- 한 점이 아니라 대형을 이룬 스웜처럼 보이게.
    private static final double BASE_BEARING_DEG = 180.0;
    private static final double BEARING_JITTER_DEG = 20.0;

    // 소형 UAS(무인기)의 현실적인 순항 속도/고도 범위 -- altitude<50 조건에 걸리는
    // 개체가 섞여 있어서 ThreatAnalysisService 규칙상 일부는 HIGH로 잡힌다.
    private static final double SPEED_MIN_KMH = 60.0;
    private static final double SPEED_MAX_KMH = 150.0;
    private static final double ALTITUDE_MIN_M = 30.0;
    private static final double ALTITUDE_MAX_M = 150.0;

    private final TargetProducer targetProducer;
    private final Random random = new Random();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> currentTask;

    private static final class DroneState {
        final String targetId;
        double lat;
        double lon;
        final double headingDeg;
        final double speedKmh;
        final double altitude;

        DroneState(String targetId, double lat, double lon, double headingDeg, double speedKmh, double altitude) {
            this.targetId = targetId;
            this.lat = lat;
            this.lon = lon;
            this.headingDeg = headingDeg;
            this.speedKmh = speedKmh;
            this.altitude = altitude;
        }
    }

    /**
     * @return 시작했으면 true, 이미 다른 시나리오가 진행 중이라 거부됐으면 false
     */
    public boolean start(int droneCount, int durationSec) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }

        List<DroneState> drones = spawnDrones(droneCount);
        long totalTicks = Math.max(1, durationSec / TICK_INTERVAL_SEC);
        log.info("[DmzSwarm] 시나리오 시작: 드론 {}대, {}초간({}틱) 진행", droneCount, durationSec, totalTicks);

        AtomicLong tickCount = new AtomicLong(0);
        currentTask = executor.scheduleAtFixedRate(() -> {
            long tick = tickCount.incrementAndGet();
            for (DroneState drone : drones) {
                advance(drone);
                publish(drone);
            }
            if (tick >= totalTicks) {
                log.info("[DmzSwarm] 시나리오 종료 ({}틱 완료, 드론 {}대)", tick, drones.size());
                running.set(false);
                currentTask.cancel(false);
            }
        }, 0, TICK_INTERVAL_SEC, TimeUnit.SECONDS);

        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private List<DroneState> spawnDrones(int droneCount) {
        List<DroneState> drones = new ArrayList<>(droneCount);
        for (int i = 1; i <= droneCount; i++) {
            double lat = SPAWN_LAT_MIN + random.nextDouble() * (SPAWN_LAT_MAX - SPAWN_LAT_MIN);
            double lon = SPAWN_LON_MIN + random.nextDouble() * (SPAWN_LON_MAX - SPAWN_LON_MIN);
            double headingDeg = BASE_BEARING_DEG + (random.nextDouble() * 2 - 1) * BEARING_JITTER_DEG;
            double speedKmh = SPEED_MIN_KMH + random.nextDouble() * (SPEED_MAX_KMH - SPEED_MIN_KMH);
            double altitude = ALTITUDE_MIN_M + random.nextDouble() * (ALTITUDE_MAX_M - ALTITUDE_MIN_M);
            drones.add(new DroneState("DMZ-SWARM-%02d".formatted(i), lat, lon, headingDeg, speedKmh, altitude));
        }
        return drones;
    }

    private void advance(DroneState drone) {
        double distanceKm = drone.speedKmh * (TICK_INTERVAL_SEC / 3600.0);
        double[] next = destinationPoint(drone.lat, drone.lon, drone.headingDeg, distanceKm);
        drone.lat = next[0];
        drone.lon = next[1];
    }

    /**
     * Destination point formula -- 현재 위치 + 방위각 + 이동거리로 다음 위치를 구한다.
     * AssetRecommendationService의 haversine(두 점 사이 거리)과 짝을 이루는 공식이지만
     * 방향이 반대다: 여긴 "거리를 안다"가 아니라 "거리로 도착점을 구한다". 네트워크나
     * 스케줄러 없이 단위 테스트 가능하도록 순수 함수로 분리했다(package-private).
     *
     * @return {latitude, longitude} (도 단위)
     */
    static double[] destinationPoint(double lat, double lon, double bearingDeg, double distanceKm) {
        double angularDistance = distanceKm / EARTH_RADIUS_KM;
        double bearingRad = Math.toRadians(bearingDeg);

        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);

        double lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(angularDistance)
                + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearingRad));
        double lon2 = lon1 + Math.atan2(
            Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(lat1),
            Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2));

        return new double[] { Math.toDegrees(lat2), Math.toDegrees(lon2) };
    }

    private void publish(DroneState drone) {
        TargetEvent event = TargetEvent.builder()
            .targetId(drone.targetId)
            .targetType("DRONE")
            .latitude(drone.lat)
            .longitude(drone.lon)
            .altitude(drone.altitude)
            .speed(drone.speedKmh)
            .status("HOSTILE")
            .heading(drone.headingDeg)
            .build();
        targetProducer.send(event);
    }
}
