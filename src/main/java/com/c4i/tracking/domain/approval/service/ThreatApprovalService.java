package com.c4i.tracking.domain.approval.service;

import com.c4i.tracking.common.exception.ApprovalNotFoundException;
import com.c4i.tracking.domain.approval.dto.ThreatApprovalDto;
import com.c4i.tracking.domain.approval.entity.ThreatApproval;
import com.c4i.tracking.domain.approval.repository.ThreatApprovalRepository;
import com.c4i.tracking.domain.asset.AssetRecommendation;
import com.c4i.tracking.domain.asset.AssetRecommendationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * "AI 판단 -> 자산 추천 -> 사람 승인 -> 결정 기록"으로 이어지는 human-in-the-loop
 * 루프. ThreatAnalysisService가 HIGH/CRITICAL을 산출하면 여기로 승인 요청이
 * 생성되고, AssetRecommendationService가 계산한 top-3 요격 자산 옵션과 함께
 * 담당자에게 제시된다. 담당자가 대시보드에서 옵션을 골라 승인하거나 반려하면
 * 그 결정이 감사 로그처럼 영구 기록된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreatApprovalService {

    private static final Set<String> APPROVAL_REQUIRED_LEVELS = Set.of("HIGH", "CRITICAL");
    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "REJECTED");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ThreatApprovalRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry meterRegistry;
    private final AssetRecommendationService assetRecommendationService;

    @Transactional
    public void createIfNeeded(String targetId, String targetType, String threatLevel, String sitrep,
                                double latitude, double longitude) {
        if (!APPROVAL_REQUIRED_LEVELS.contains(threatLevel)) return;

        // 같은 표적이 쿨다운 내 반복 분석/폴링으로 여러 번 HIGH/CRITICAL로 잡혀도
        // 이미 대기 중인 승인 요청이 있으면 중복 생성하지 않는다.
        boolean alreadyPending = repository
            .findFirstByTargetIdAndStatusOrderByRequestedAtDesc(targetId, "PENDING")
            .isPresent();
        if (alreadyPending) return;

        List<AssetRecommendation> options = assetRecommendationService.recommend(targetType, latitude, longitude);

        ThreatApproval approval = ThreatApproval.builder()
            .targetId(targetId)
            .targetType(targetType)
            .threatLevel(threatLevel)
            .sitrep(sitrep)
            .recommendedOptionsJson(writeJson(options))
            .build();
        repository.save(approval);

        log.info("[ThreatApproval] 승인 요청 생성: targetId={}, threatLevel={}", targetId, threatLevel);
        Counter.builder("threat_approval_requested_total")
            .description("생성된 승인 요청 수")
            .tag("threatLevel", threatLevel)
            .register(meterRegistry)
            .increment();
        messagingTemplate.convertAndSend("/topic/approvals", ThreatApprovalDto.Response.from(approval));
    }

    public List<ThreatApprovalDto.Response> listPending() {
        return repository.findByStatusOrderByRequestedAtDesc("PENDING").stream()
            .map(ThreatApprovalDto.Response::from)
            .toList();
    }

    public List<ThreatApprovalDto.Response> listAll() {
        return repository.findAllByOrderByRequestedAtDesc().stream()
            .map(ThreatApprovalDto.Response::from)
            .toList();
    }

    @Transactional
    public ThreatApprovalDto.Response decide(Long id, ThreatApprovalDto.DecisionRequest request) {
        ThreatApproval approval = repository.findById(id)
            .orElseThrow(() -> new ApprovalNotFoundException(id));

        String decision = request.getDecision();
        if (!VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("decision은 APPROVED 또는 REJECTED만 허용됩니다: " + decision);
        }

        // MSS의 "3~4개 옵션 중 하나 클릭"과 동일하게, 승인은 추천된 옵션 중
        // 실제로 하나를 골라야 성립한다 -- 반려는 옵션 선택 없이도 가능.
        String requestedOption = request.getSelectedOption();
        String selectedOption = null;
        if ("APPROVED".equals(decision)) {
            List<AssetRecommendation> options = readJson(approval.getRecommendedOptionsJson());
            boolean validSelection = options.stream().anyMatch(o -> o.assetName().equals(requestedOption));
            if (!validSelection) {
                throw new IllegalArgumentException(
                    "승인하려면 추천된 자산 옵션 중 하나를 selectedOption으로 지정해야 합니다: " + requestedOption);
            }
            selectedOption = requestedOption;
        }

        var requestedAt = approval.getRequestedAt();
        approval.decide(decision, request.getDecidedBy(), request.getReason(), selectedOption);
        log.info("[ThreatApproval] 승인 결정: id={}, targetId={}, decision={}, decidedBy={}",
            id, approval.getTargetId(), decision, request.getDecidedBy());

        Counter.builder("threat_approval_decided_total")
            .description("결정 완료된 승인 요청 수")
            .tag("decision", decision)
            .register(meterRegistry)
            .increment();
        // 요청 생성 -> 사람이 실제로 결정하기까지 걸린 시간. 담당자가 승인 대기열을
        // 얼마나 빨리 처리하는지 보여주는 지표라 human-in-the-loop 루프의 핵심 관측값.
        Timer.builder("threat_approval_time_to_decision_seconds")
            .description("승인 요청 생성부터 사람이 결정하기까지 걸린 시간")
            .register(meterRegistry)
            .record(Duration.between(requestedAt, approval.getDecidedAt()));

        ThreatApprovalDto.Response response = ThreatApprovalDto.Response.from(approval);
        messagingTemplate.convertAndSend("/topic/approvals", response);
        return response;
    }

    private String writeJson(List<AssetRecommendation> options) {
        try {
            return OBJECT_MAPPER.writeValueAsString(options);
        } catch (Exception e) {
            log.warn("[ThreatApproval] 추천 옵션 직렬화 실패, 빈 배열로 저장: {}", e.getMessage());
            return "[]";
        }
    }

    private List<AssetRecommendation> readJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readerForListOf(AssetRecommendation.class).readValue(json);
        } catch (Exception e) {
            return List.of();
        }
    }
}
