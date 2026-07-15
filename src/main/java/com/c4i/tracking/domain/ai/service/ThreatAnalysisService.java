package com.c4i.tracking.domain.ai.service;

import com.c4i.tracking.domain.ai.dto.ThreatAnalysisDto;
import com.c4i.tracking.kafka.TargetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 기반 위협 분석 파이프라인:
 *   1. 표적 정보 → 자연어 설명문 구성
 *   2. pgvector에서 코사인 유사도로 유사 위협 패턴 검색 (Retrieval)
 *   3. 유사 패턴 + 현재 상황을 LLM에 전달 → SITREP 생성 (Augmented Generation)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreatAnalysisService {

    // 무료 tier 일일 호출 한도를 넘지 않도록 targetId별 최소 분석 간격
    private static final Duration ANALYSIS_COOLDOWN = Duration.ofMinutes(10);

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final Map<String, Instant> lastAnalyzedAt = new ConcurrentHashMap<>();

    @Value("${spring.ai.google.genai.api-key:PLACEHOLDER}")
    private String apiKey;

    public boolean isAiEnabled() {
        return apiKey != null && !apiKey.isBlank() && !"PLACEHOLDER".equals(apiKey);
    }

    private boolean shouldAnalyze(String targetId) {
        Instant now = Instant.now();
        // 쿨다운이 지났을 때만 타임스탬프를 갱신 (건너뛴 호출이 기준 시각을 밀어내지 않도록 원자적으로 처리)
        Instant updated = lastAnalyzedAt.compute(targetId, (key, previous) ->
                previous == null || Duration.between(previous, now).compareTo(ANALYSIS_COOLDOWN) >= 0
                        ? now
                        : previous);
        return updated == now;
    }

    /**
     * Kafka Consumer에서 호출하는 비동기 분석 (WebSocket 전송을 블로킹하지 않음).
     * 같은 targetId는 쿨다운 기간 내 재호출을 건너뛰어 LLM API 호출량을 제한한다.
     */
    @Async("aiAnalysisExecutor")
    public void analyzeAsync(TargetEvent event) {
        if (!isAiEnabled()) return;
        if (!shouldAnalyze(event.getTargetId())) return;
        try {
            ThreatAnalysisDto.Response result = analyze(event);
            log.info("[ThreatAI] targetId={} | threatLevel={} | sitrep={}",
                result.getTargetId(), result.getThreatLevel(),
                result.getSitrep().substring(0, Math.min(80, result.getSitrep().length())));
        } catch (Exception e) {
            log.error("[ThreatAI] 비동기 분석 실패: targetId={}, error={}", event.getTargetId(), e.getMessage());
        }
    }

    /**
     * REST API에서 호출하는 동기 분석.
     */
    public ThreatAnalysisDto.Response analyze(TargetEvent event) {
        String targetDescription = buildDescription(event);
        String ruleBasedLevel = calculateRuleBasedThreatLevel(event);

        if (!isAiEnabled()) {
            return ThreatAnalysisDto.Response.builder()
                .targetId(event.getTargetId())
                .targetType(event.getTargetType())
                .threatLevel(ruleBasedLevel)
                .sitrep("AI 분석 비활성화. GEMINI_API_KEY 환경변수 설정 후 재시작하면 LLM 기반 SITREP이 생성됩니다.")
                .similarPatterns(List.of())
                .aiEnabled(false)
                .build();
        }

        // Step 1: Retrieval — pgvector 유사 위협 패턴 검색
        List<Document> similar = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(targetDescription)
                .topK(3)
                .similarityThreshold(0.5)
                .build()
        );

        List<String> similarPatterns = similar.stream()
            .map(Document::getText)
            .toList();

        // Step 2: Augmented Generation — 유사 패턴을 컨텍스트로 LLM SITREP 생성
        String sitrep = generateSitrep(targetDescription, similarPatterns, ruleBasedLevel);

        return ThreatAnalysisDto.Response.builder()
            .targetId(event.getTargetId())
            .targetType(event.getTargetType())
            .threatLevel(ruleBasedLevel)
            .sitrep(sitrep)
            .similarPatterns(similarPatterns)
            .aiEnabled(true)
            .build();
    }

    private String generateSitrep(String description, List<String> patterns, String threatLevel) {
        String context = patterns.isEmpty()
            ? "유사 위협 패턴 없음 (신규 유형 가능성)"
            : String.join("\n---\n", patterns);

        String prompt = """
            당신은 대한민국 방공 지휘통제(C4I) AI입니다. 아래 전술 상황을 분석하여 간결한 한국어 SITREP을 작성하세요.

            [현재 표적 정보]
            %s

            [위협 등급 (규칙 기반 선평가)]
            %s

            [유사 위협 패턴 데이터베이스 검색 결과]
            %s

            다음 형식으로 작성하세요:
            1. 상황 요약: (표적 특성 1-2문장)
            2. 위협 평가: (위협 등급 근거 및 유사 패턴 비교)
            3. 권고 조치: (즉각 취해야 할 행동 3가지 이내)
            """.formatted(description, threatLevel, context);

        return chatModel.call(prompt);
    }

    private String buildDescription(TargetEvent event) {
        return "표적ID=%s, 유형=%s, 위도=%.4f, 경도=%.4f, 고도=%.0fm, 속도=%.0fkm/h, 상태=%s"
            .formatted(event.getTargetId(), event.getTargetType(),
                event.getLatitude(), event.getLongitude(),
                event.getAltitude(), event.getSpeed(), event.getStatus());
    }

    /**
     * LLM 호출 이전에 기본 위협 등급을 산출하는 규칙 기반 사전 평가.
     * AI 비활성화 상태에서도 단독으로 동작 가능.
     */
    private String calculateRuleBasedThreatLevel(TargetEvent event) {
        String type = event.getTargetType();
        double speed = event.getSpeed();
        double altitude = event.getAltitude();

        if ("MISSILE".equals(type)) return "CRITICAL";
        if ("DRONE".equals(type) && speed > 250 && altitude < 100) return "CRITICAL";
        if ("DRONE".equals(type) && altitude < 50) return "HIGH";
        if ("AIRCRAFT".equals(type) && speed > 800 && altitude < 500) return "HIGH";
        if (speed > 200) return "MEDIUM";
        return "LOW";
    }
}
