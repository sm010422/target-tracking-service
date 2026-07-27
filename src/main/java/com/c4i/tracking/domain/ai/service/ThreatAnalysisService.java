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

    // 무료 tier 호출 한도를 넘지 않도록 targetId별 최소 분석 간격.
    // 군용기(status=MILITARY)는 반경을 벗어나지 않는 한 매 폴링 주기(20초)마다
    // Kafka 이벤트가 계속 들어오므로, 쿨다운이 짧으면 정찰 중인 자산 하나가
    // 하루 종일 백그라운드에서 embedding 쿼터를 갉아먹어 정작 사용자가 수동으로
    // "AI 위협분석" 버튼을 누를 때 쿼터가 없는 상황(429)이 실제로 발생했다.
    // 10분 -> 30분으로 늘려 수동 분석에 쓸 쿼터 여유를 남긴다.
    private static final Duration ANALYSIS_COOLDOWN = Duration.ofMinutes(30);

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
     * AdsbFiPollingService가 20초마다 서로 다른 민항기 수십 대를 흘려보내면서,
     * 전부 AI 분석에 넣었더니 aiAnalysisExecutor 큐(용량 100)가 넘쳐서
     * RejectedExecutionException이 실제로 발생했다 (targetId별 10분 쿨다운은
     * 있지만, 대부분의 항공기가 반경을 스쳐 지나가는 1~2분 안에 다시는 안 보여서
     * 쿨다운이 사실상 무력화됨).
     *
     * 평범하게 순항 중인 민항기(등급 MEDIUM 이하, 군용 아님)는 AI 분석을 건너뛰고
     * 규칙 기반 등급만 적용한다 -- 어차피 실시간 SITREP까지 필요한 케이스가
     * 아니다. DRONE/MISSILE(가짜 시뮬레이터, 개체 수가 적음)과 군용기/이례적으로
     * 위험한 민항기(HIGH 이상)는 그대로 전부 분석한다.
     */
    private boolean warrantsAiAnalysis(TargetEvent event) {
        if (!"AIRCRAFT".equals(event.getTargetType())) return true;
        if ("MILITARY".equals(event.getStatus())) return true;
        String level = calculateRuleBasedThreatLevel(event);
        return "HIGH".equals(level) || "CRITICAL".equals(level);
    }

    /**
     * Kafka Consumer에서 호출하는 비동기 분석 (WebSocket 전송을 블로킹하지 않음).
     * 같은 targetId는 쿨다운 기간 내 재호출을 건너뛰어 LLM API 호출량을 제한한다.
     */
    @Async("aiAnalysisExecutor")
    public void analyzeAsync(TargetEvent event) {
        if (!isAiEnabled()) return;
        if (!shouldAnalyze(event.getTargetId())) return;
        if (!warrantsAiAnalysis(event)) return;
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

        try {
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
        } catch (Exception e) {
            // Gemini 무료 tier의 embedding/chat 호출량 한도(429)에 실제로 걸린 사례가 있었다 --
            // ADS-B가 흘려보내는 군용기/고위험 표적에 대해 백그라운드 자동분석(analyzeAsync)이
            // 계속 돌면서 같은 하루치 free tier 쿼터를 수동 "AI 위협분석" 버튼 클릭과 공유하기
            // 때문에, 사용자가 아무 문제 없는 표적을 클릭해도 그 순간 쿼터가 없으면 실패할 수 있다.
            // 이걸 그냥 HTTP 500으로 던지는 대신, 규칙 기반 등급이라도 정상적으로 보여준다.
            log.warn("[ThreatAI] LLM 호출 실패 (쿼터/네트워크), 규칙 기반 등급으로 대체: targetId={}, error={}",
                event.getTargetId(), e.getMessage());
            return ThreatAnalysisDto.Response.builder()
                .targetId(event.getTargetId())
                .targetType(event.getTargetType())
                .threatLevel(ruleBasedLevel)
                .sitrep("⚠️ AI 분석 일시 실패 (Gemini API 요청량 제한 또는 일시 오류) — 규칙 기반 등급만 표시됩니다. 잠시 후 다시 시도해주세요.")
                .similarPatterns(List.of())
                .aiEnabled(true)
                .build();
        }
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

        String level;
        if ("MISSILE".equals(type)) level = "CRITICAL";
        else if ("DRONE".equals(type) && speed > 250 && altitude < 100) level = "CRITICAL";
        else if ("DRONE".equals(type) && altitude < 50) level = "HIGH";
        else if ("AIRCRAFT".equals(type) && speed > 800 && altitude < 500) level = "HIGH";
        else if (speed > 200) level = "MEDIUM";
        else level = "LOW";

        // AdsbFiPollingService가 실제 군용기(adsb.fi /v2/mil 대조)로 확인한 표적은
        // status="MILITARY"로 들어온다. 군용 자산이라는 사실만으로 CRITICAL을 단정하진
        // 않되(순찰 중인 자국 헬기까지 전부 최고 등급으로 잡으면 무의미하니), 한 단계
        // 올려서 "군용이라 더 주시해야 한다"는 정도로만 반영한다.
        if ("MILITARY".equals(event.getStatus())) {
            level = escalate(level);
        }
        return level;
    }

    private String escalate(String level) {
        return switch (level) {
            case "LOW" -> "MEDIUM";
            case "MEDIUM" -> "HIGH";
            default -> level; // HIGH/CRITICAL은 이미 최고 수준 근처라 그대로 둠
        };
    }
}
