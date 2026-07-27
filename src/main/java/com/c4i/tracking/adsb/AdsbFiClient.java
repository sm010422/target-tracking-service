package com.c4i.tracking.adsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

/**
 * opendata.adsb.fi 공개 API 클라이언트.
 *
 * 실제 base URL은 https://opendata.adsb.fi/api/v3/... 이다 (adsb.lol과 경로 구조가 다름 --
 * 처음에 adsb.lol과 같은 형태로 추정하고 호출했다가 404를 받고서야 실제 문서를 확인했다).
 *
 * 이용약관: 개인/비상업적 용도만 허용, 출처 표시 필수, 재판매 금지
 * (https://adsb.fi 참고). 상업적 재배포가 아닌 포트폴리오 프로젝트의 대시보드
 * 표시 + Kafka 파이프라인 실습용으로만 사용한다.
 */
@Slf4j
@Component
public class AdsbFiClient {

    private static final String BASE_URL = "https://opendata.adsb.fi/api";
    private static final String USER_AGENT = "target-tracking-service-c4i-portfolio/1.0 (github.com/sm010422/target-tracking-service)";

    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 지정한 중심 좌표 반경(해리) 내 항공기 목록. 실패 시 빈 배열 노드를 반환한다
     * (호출부가 매번 null 체크를 안 해도 되도록).
     */
    public JsonNode fetchAircraftNear(double lat, double lon, int radiusNm) {
        String url = "%s/v3/lat/%s/lon/%s/dist/%s".formatted(BASE_URL, lat, lon, radiusNm);
        try {
            String body = restClient.get()
                .uri(url)
                .header("User-Agent", USER_AGENT)
                .retrieve()
                .body(String.class);
            return objectMapper.readTree(body).path("ac");
        } catch (Exception e) {
            log.warn("[AdsbFi] 반경 조회 실패 ({}, {}, {}nm): {}", lat, lon, radiusNm, e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /**
     * 전 세계 군용기로 표시된 hex 코드 집합. 이 API는 개별 항공기 레코드에
     * "military" 같은 불리언 필드를 주지 않는다 -- /v2/mil로 별도 조회해서
     * hex 코드를 대조하는 방식으로만 판별 가능하다.
     */
    public Set<String> fetchMilitaryHexes() {
        try {
            String body = restClient.get()
                .uri(BASE_URL + "/v2/mil")
                .header("User-Agent", USER_AGENT)
                .retrieve()
                .body(String.class);
            JsonNode ac = objectMapper.readTree(body).path("ac");
            Set<String> hexes = new HashSet<>();
            ac.forEach(node -> hexes.add(node.path("hex").asText()));
            return hexes;
        } catch (Exception e) {
            log.warn("[AdsbFi] 군용기 목록 조회 실패, 이번 주기는 군용 판별 생략: {}", e.getMessage());
            return Set.of();
        }
    }
}
