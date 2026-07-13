package com.c4i.tracking.domain.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 애플리케이션 시작 시 방산 위협 패턴 지식 베이스를 pgvector에 적재.
 * GEMINI_API_KEY 미설정 시 임베딩 호출이 불가하므로 초기화를 건너뜀.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(100)
public class ThreatKnowledgeInitializer implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.ai.google.genai.api-key:PLACEHOLDER}")
    private String apiKey;

    private static final String KB_SOURCE = "c4i-threat-kb";

    private static final List<String> THREAT_PATTERNS = List.of(
        // ── 드론 ──────────────────────────────────────────────────────────
        "표적유형: DRONE, 행동패턴: 저속(30-80km/h) 저고도(50-150m) 경계선 순찰, 위협등급: MEDIUM, " +
        "분석: 광학/열화상 센서 탑재 정찰 드론 추정. 아군 진지·장비 위치 촬영 가능. " +
        "권고조치: 전파 교란(재밍) 우선 적용, 포획망 드론 출격 대기, 이동 경로 기록 후 발진지 역탐지.",

        "표적유형: DRONE, 행동패턴: 고속(250-400km/h) 초저고도(0-50m) 목표지점 직선 접근, 위협등급: CRITICAL, " +
        "분석: 폭발물 탑재 자폭 드론(loitering munition) 추정. 충돌까지 잔여 시간 극히 짧음. " +
        "권고조치: 즉각 요격 - 대공포 또는 대드론 레이저 무기 우선. 인원 즉시 대피.",

        "표적유형: DRONE, 행동패턴: 다수 개체(5개 이상) 중속(100-200km/h) 분산 접근, 위협등급: HIGH, " +
        "분석: 군집 드론(swarm) 포화 공격 전술. 단일 요격 수단으로는 대응 제한. " +
        "권고조치: 다중 재밍 동시 운용, 군집 제어 주파수 탐지 후 재밍 집중, 광역 방어 요청.",

        "표적유형: DRONE, 행동패턴: 불규칙 지그재그 비행, GPS 신호 이상 감지, 위협등급: MEDIUM, " +
        "분석: EMP 또는 전자전 장비 탑재 드론으로 아군 GPS·통신 교란 목적 추정. " +
        "권고조치: 무선 통신 최소화, 백업 INS 항법 전환, 드론 격추 또는 재밍으로 교란 차단.",

        // ── 미사일 ────────────────────────────────────────────────────────
        "표적유형: MISSILE, 행동패턴: 중속(700-900km/h) 초저고도(10-50m) 지형 따라 비행, 위협등급: CRITICAL, " +
        "분석: 순항 미사일(cruise missile) 추정. 레이더 회피를 위한 지면 밀착 비행. " +
        "권고조치: 즉각 SAM 발사. 단거리 방공망(SHORAD) 전방 전개, 중요 시설 긴급 대피령.",

        "표적유형: MISSILE, 행동패턴: 극초고속(마하 5+) 고고도 탄도 궤적, 위협등급: CRITICAL, " +
        "분석: 탄도 미사일(SRBM/MRBM) 추정. 상승→하강 탄도 비행. " +
        "권고조치: 즉각 PAC-3 또는 THAAD 요격 절차 개시. 전국 민방위 경보 발령.",

        // ── 항공기 ────────────────────────────────────────────────────────
        "표적유형: AIRCRAFT, 행동패턴: 고속(800-1000km/h) 고고도(5000-10000m) 직선 비행, 위협등급: LOW, " +
        "분석: 민간 항공기 또는 우군 전투기 정상 순항 비행 패턴. " +
        "권고조치: IFF(피아식별) 응답 확인. 비정상 응답 시 MEDIUM으로 위협 등급 상향.",

        "표적유형: AIRCRAFT, 행동패턴: 고속(900-1500km/h) 급격한 고도·방향 변화, 저고도 기동, 위협등급: HIGH, " +
        "분석: 적 전술 전투기 공격 기동 추정. 지상 공격 또는 아군기 교전 준비 중. " +
        "권고조치: 공중 전투기 즉시 스크램블, 장거리 SAM 목표 지정, 지상 인원 산개.",

        // ── 복합 / 기타 ───────────────────────────────────────────────────
        "표적유형: DRONE, 행동패턴: 야간(00:00-04:00) 저속 저고도 접근, 적외선 신호 약함, 위협등급: HIGH, " +
        "분석: 스텔스 소재 야간 침투 드론으로 특수부대 침투 유도 또는 요인 암살 목적 추정. " +
        "권고조치: 열화상 탐지 드론 출격, 레이더 교차 확인, 경계 인원 야간 투시 장비 가동.",

        "표적유형: AIRCRAFT, 행동패턴: 민항기 식별코드 응답하나 비정상 경로·고도, 위협등급: HIGH, " +
        "분석: 민항기로 위장한 적 정찰기 또는 납치된 항공기 가능성. " +
        "권고조치: 관제탑 교신 시도, 전투기 근접 식별(visual ID), 납치 가능성 시 비상 절차 가동."
    );

    @Override
    public void run(ApplicationArguments args) {
        if (isApiKeyPlaceholder()) {
            log.warn("[ThreatAI] GEMINI_API_KEY 미설정 - 위협 지식 베이스 초기화 건너뜀. " +
                     "export GEMINI_API_KEY=<your-key> 후 재시작하면 AI 기능이 활성화됩니다.");
            return;
        }

        if (isAlreadySeeded()) {
            log.info("[ThreatAI] 위협 지식 베이스가 이미 적재되어 있어 초기화를 건너뜀");
            return;
        }

        try {
            log.info("[ThreatAI] 위협 지식 베이스 초기화 시작 ({} 개 패턴)", THREAT_PATTERNS.size());
            List<Document> documents = THREAT_PATTERNS.stream()
                .map(content -> new Document(content, Map.of("source", KB_SOURCE)))
                .toList();
            vectorStore.add(documents);
            log.info("[ThreatAI] 위협 지식 베이스 초기화 완료");
        } catch (Exception e) {
            log.error("[ThreatAI] 위협 지식 베이스 초기화 실패: {}", e.getMessage());
        }
    }

    private boolean isApiKeyPlaceholder() {
        return apiKey == null || apiKey.isBlank() || "PLACEHOLDER".equals(apiKey);
    }

    /**
     * pgvector 테이블에 이미 지식 베이스가 적재됐는지 확인.
     * Pod 재시작/재배포 시마다 중복 삽입되는 것을 방지.
     */
    private boolean isAlreadySeeded() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'source' = ?",
            Integer.class, KB_SOURCE);
        return count != null && count > 0;
    }
}
