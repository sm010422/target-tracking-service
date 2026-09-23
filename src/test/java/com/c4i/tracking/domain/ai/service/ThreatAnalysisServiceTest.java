package com.c4i.tracking.domain.ai.service;

import com.c4i.tracking.domain.ai.dto.ThreatAnalysisDto;
import com.c4i.tracking.domain.approval.service.ThreatApprovalService;
import com.c4i.tracking.kafka.TargetEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AI(Gemini) 호출 없이 규칙 기반 위협 등급 산출 로직만 검증한다. apiKey를 비워서
 * isAiEnabled()=false로 만들면 analyze()가 AI 호출 없이 규칙 기반 등급만 반환하는
 * 경로(target-tracking-service/docs/ai-analysis.md의 "규칙 기반 위협 등급" 표)를
 * VectorStore/ChatModel 목킹 없이도 그대로 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class ThreatAnalysisServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatModel chatModel;

    @Mock
    private ThreatApprovalService threatApprovalService;

    private ThreatAnalysisService threatAnalysisService;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        threatAnalysisService = new ThreatAnalysisService(vectorStore, chatModel, threatApprovalService, meterRegistry);
        ReflectionTestUtils.setField(threatAnalysisService, "apiKey", "PLACEHOLDER");
    }

    static Stream<Arguments> ruleBasedCases() {
        return Stream.of(
            Arguments.of("MISSILE", 500.0, 300.0, "DETECTED", "CRITICAL"),
            Arguments.of("DRONE", 80.0, 280.0, "DETECTED", "CRITICAL"),
            Arguments.of("DRONE", 30.0, 100.0, "DETECTED", "HIGH"),
            Arguments.of("AIRCRAFT", 400.0, 850.0, "DETECTED", "HIGH"),
            Arguments.of("AIRCRAFT", 5000.0, 250.0, "DETECTED", "MEDIUM"),
            Arguments.of("AIRCRAFT", 5000.0, 100.0, "DETECTED", "LOW")
        );
    }

    @ParameterizedTest(name = "{0} 고도={1} 속도={2} status={3} -> {4}")
    @MethodSource("ruleBasedCases")
    @DisplayName("규칙 기반 위협 등급을 표 그대로 산출한다")
    void calculatesRuleBasedThreatLevel(String type, double altitude, double speed, String status, String expected) {
        TargetEvent event = TargetEvent.builder()
            .targetId("T-1").targetType(type).latitude(37.5).longitude(127.0)
            .altitude(altitude).speed(speed).status(status).build();

        ThreatAnalysisDto.Response response = threatAnalysisService.analyze(event);

        assertThat(response.getThreatLevel()).isEqualTo(expected);
        assertThat(response.isAiEnabled()).isFalse();
    }

    @Test
    @DisplayName("MILITARY 상태면 등급을 한 단계 상향(escalate)한다")
    void militaryStatusEscalatesLevel() {
        // LOW -> MEDIUM으로 올라가는지 확인 (AIRCRAFT, 고고도/저속이라 원래는 LOW)
        TargetEvent event = TargetEvent.builder()
            .targetId("T-2").targetType("AIRCRAFT").latitude(37.5).longitude(127.0)
            .altitude(5000.0).speed(100.0).status("MILITARY").build();

        ThreatAnalysisDto.Response response = threatAnalysisService.analyze(event);

        assertThat(response.getThreatLevel()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("AI가 비활성화여도 규칙 기반 등급만으로 승인 요청 훅은 그대로 호출된다")
    void requestsApprovalEvenWhenAiDisabled() {
        // AI 비활성화 분기는 sitrep이 안내 문구일 뿐이라도 threatLevel은 여전히 규칙
        // 기반으로 계산되므로, "AI 없이도 사람 승인 루프는 살아있어야 한다"는 설계
        // 원칙(docs/threat-approval.md)에 따라 createIfNeeded는 항상 호출돼야 한다.
        TargetEvent event = TargetEvent.builder()
            .targetId("T-3").targetType("MISSILE").latitude(37.5).longitude(127.0)
            .altitude(500.0).speed(300.0).status("DETECTED").build();

        threatAnalysisService.analyze(event);

        verify(threatApprovalService).createIfNeeded("T-3", "MISSILE", "CRITICAL",
            "AI 분석 비활성화. GEMINI_API_KEY 환경변수 설정 후 재시작하면 LLM 기반 SITREP이 생성됩니다.");
        verifyNoInteractions(vectorStore, chatModel);
    }
}
