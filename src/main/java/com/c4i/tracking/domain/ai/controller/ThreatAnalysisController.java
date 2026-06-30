package com.c4i.tracking.domain.ai.controller;

import com.c4i.tracking.domain.ai.dto.ThreatAnalysisDto;
import com.c4i.tracking.domain.ai.service.ThreatAnalysisService;
import com.c4i.tracking.kafka.TargetEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/threat-analysis")
@RequiredArgsConstructor
public class ThreatAnalysisController {

    private final ThreatAnalysisService threatAnalysisService;

    /**
     * GET /api/v1/threat-analysis/status
     * AI 기능 활성화 여부 확인. API 키 설정 전 상태 점검용.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        boolean enabled = threatAnalysisService.isAiEnabled();
        return ResponseEntity.ok(Map.of(
            "aiEnabled", enabled,
            "message", enabled
                ? "AI 위협 분석 활성화됨 (RAG + pgvector)"
                : "AI 비활성화 - export OPENAI_API_KEY=<your-key> 후 재시작 필요"
        ));
    }

    /**
     * POST /api/v1/threat-analysis/analyze
     * 표적 정보를 입력받아 RAG 기반 위협 분석 및 SITREP 반환.
     *
     * Request body 예시:
     * {
     *   "targetId": "DRONE-001",
     *   "targetType": "DRONE",
     *   "latitude": 37.5,
     *   "longitude": 127.0,
     *   "altitude": 45.0,
     *   "speed": 280.0,
     *   "status": "DETECTED"
     * }
     */
    @PostMapping("/analyze")
    public ResponseEntity<ThreatAnalysisDto.Response> analyze(
            @RequestBody ThreatAnalysisDto.Request request) {

        TargetEvent event = TargetEvent.builder()
            .targetId(request.getTargetId())
            .targetType(request.getTargetType())
            .latitude(request.getLatitude())
            .longitude(request.getLongitude())
            .altitude(request.getAltitude())
            .speed(request.getSpeed())
            .status(request.getStatus())
            .build();

        return ResponseEntity.ok(threatAnalysisService.analyze(event));
    }
}
