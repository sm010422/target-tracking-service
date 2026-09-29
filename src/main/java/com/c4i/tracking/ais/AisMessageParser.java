package com.c4i.tracking.ais;

import com.c4i.tracking.kafka.TargetEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * aisstream.io가 WebSocket으로 보내는 AIS 메시지 JSON을 기존 Kafka 파이프라인이
 * 쓰는 TargetEvent로 변환한다. AdsbFiPollingService의 toTargetEvent()와 같은 역할이지만,
 * 연결 관리(AisStreamService)와 분리해서 이 파싱 로직만 네트워크 없이 단위 테스트 가능하게 했다.
 *
 * PositionReport 메시지만 다룬다 (aisstream이 구독 시 FilterMessageTypes로 이것만 보내도록
 * 요청함). TrueHeading은 AIS 스펙상 511이 "값 없음"을 의미하므로 그 경우 Cog(대지 진행방향)로
 * 대체한다 -- 둘 다 없으면 null로 둬서 프론트엔드(MapView)가 방향을 안다고 거짓으로 암시하지 않게 한다.
 */
@Component
public class AisMessageParser {

    private static final int HEADING_NOT_AVAILABLE = 511;
    private static final double KNOTS_TO_KMH = 1.852;

    public TargetEvent parse(JsonNode message) {
        if (!"PositionReport".equals(message.path("MessageType").asText(""))) {
            return null; // 구독 필터로 대부분 걸러지지만, 방어적으로 한 번 더 확인
        }

        JsonNode report = message.path("Message").path("PositionReport");
        JsonNode metaData = message.path("MetaData");

        long mmsi = metaData.path("MMSI").asLong(report.path("UserID").asLong(0));
        if (mmsi <= 0) return null; // MMSI 없는 레코드는 targetId를 만들 수 없어 스킵

        double lat = metaData.hasNonNull("latitude") ? metaData.path("latitude").asDouble()
            : report.path("Latitude").asDouble(Double.NaN);
        double lon = metaData.hasNonNull("longitude") ? metaData.path("longitude").asDouble()
            : report.path("Longitude").asDouble(Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;

        double speedKmh = report.path("Sog").asDouble(0.0) * KNOTS_TO_KMH;

        Integer trueHeading = report.hasNonNull("TrueHeading") ? report.path("TrueHeading").asInt() : null;
        Double heading;
        if (trueHeading != null && trueHeading != HEADING_NOT_AVAILABLE) {
            heading = trueHeading.doubleValue();
        } else if (report.hasNonNull("Cog")) {
            heading = report.path("Cog").asDouble();
        } else {
            heading = null;
        }

        return TargetEvent.builder()
            .targetId(String.valueOf(mmsi))
            .targetType("SHIP")
            .latitude(lat)
            .longitude(lon)
            .altitude(0.0) // 해수면 기준 -- 선박에 고도 개념 없음
            .speed(speedKmh)
            .status("DETECTED")
            .heading(heading)
            .build();
    }
}
