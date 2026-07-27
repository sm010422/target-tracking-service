package com.c4i.tracking.adsb;

import com.c4i.tracking.kafka.TargetEvent;
import com.c4i.tracking.kafka.TargetProducer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DroneSimulator(가짜 난수 드론)와 별개로, opendata.adsb.fi의 실제 공개 ADS-B 피드를
 * 끌어와 기존 Kafka 파이프라인(TargetProducer → target-tracking 토픽)에 그대로 흘려보낸다.
 *
 * 이 서비스가 TargetEvent를 발행하는 순간부터는 기존 파이프라인이 손댈 것 없이 전부 처리한다:
 *   TargetConsumer → PostgreSQL 저장 + WebSocket 브로드캐스트(대시보드) + AI 위협분석(쿨다운 적용)
 *   threat-intel-ai-service → 별도 consumer group으로 Qdrant 이력 색인
 *
 * targetType="AIRCRAFT"로 발행하므로 ThreatAnalysisService의 규칙 기반 등급(예:
 * 저고도 고속 AIRCRAFT → HIGH)이 실제 항공기에도 그대로 적용된다. 군용기로 확인되면
 * status="MILITARY"로 표시하고, 이는 규칙 엔진에서 등급을 한 단계 올리는 데 쓰인다
 * (자세한 내용은 ThreatAnalysisService 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdsbFiPollingService {

    private final AdsbFiClient adsbFiClient;
    private final TargetProducer targetProducer;

    @Value("${adsb.enabled:false}")
    private boolean enabled;

    @Value("${adsb.center-lat:37.5665}")
    private double centerLat;

    @Value("${adsb.center-lon:126.9780}")
    private double centerLon;

    @Value("${adsb.radius-nm:100}")
    private int radiusNm;

    @Scheduled(fixedRateString = "${adsb.poll-interval-ms:20000}")
    public void poll() {
        if (!enabled) return;

        Set<String> militaryHexes = adsbFiClient.fetchMilitaryHexes();
        JsonNode aircraft = adsbFiClient.fetchAircraftNear(centerLat, centerLon, radiusNm);

        int published = 0;
        for (JsonNode ac : aircraft) {
            TargetEvent event = toTargetEvent(ac, militaryHexes);
            if (event == null) continue;
            targetProducer.send(event);
            published++;
        }
        log.info("[AdsbFi] 중심({}, {}) 반경 {}nm: {}대 발행 (군용 목록 {}대 대조)",
            centerLat, centerLon, radiusNm, published, militaryHexes.size());
    }

    private TargetEvent toTargetEvent(JsonNode ac, Set<String> militaryHexes) {
        String hex = ac.path("hex").asText(null);
        if (hex == null || hex.isBlank()) return null;

        double lat = ac.path("lat").asDouble(Double.NaN);
        double lon = ac.path("lon").asDouble(Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return null; // 위치 없는 레코드(지상국 신호 등)는 지도에 찍을 수 없으니 스킵
        }

        // alt_baro는 숫자(피트) 또는 문자열 "ground"로 온다 -- 지상에 있으면 고도 0으로 처리
        JsonNode altNode = ac.path("alt_baro");
        double altitudeMeters = altNode.isNumber() ? altNode.asDouble() * 0.3048 : 0.0;
        double speedKmh = ac.path("gs").asDouble(0.0) * 1.852;

        String flight = ac.path("flight").asText("").trim();
        String targetId = !flight.isBlank() ? flight : hex;
        boolean isMilitary = militaryHexes.contains(hex);

        return TargetEvent.builder()
            .targetId(targetId)
            .targetType("AIRCRAFT")
            .latitude(lat)
            .longitude(lon)
            .altitude(altitudeMeters)
            .speed(speedKmh)
            .status(isMilitary ? "MILITARY" : "DETECTED")
            .build();
    }
}
