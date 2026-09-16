package com.c4i.tracking.domain.approval.service;

import com.c4i.tracking.common.exception.ApprovalNotFoundException;
import com.c4i.tracking.domain.approval.dto.ThreatApprovalDto;
import com.c4i.tracking.domain.approval.entity.ThreatApproval;
import com.c4i.tracking.domain.approval.repository.ThreatApprovalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * "AI 판단 -> 사람 승인 -> 결정 기록"으로 이어지는 human-in-the-loop 루프.
 * ThreatAnalysisService가 HIGH/CRITICAL을 산출하면 여기로 승인 요청이 생성되고,
 * 담당자가 대시보드에서 승인/반려하면 그 결정이 감사 로그처럼 영구 기록된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreatApprovalService {

    private static final Set<String> APPROVAL_REQUIRED_LEVELS = Set.of("HIGH", "CRITICAL");
    private static final Set<String> VALID_DECISIONS = Set.of("APPROVED", "REJECTED");

    private final ThreatApprovalRepository repository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void createIfNeeded(String targetId, String targetType, String threatLevel, String sitrep) {
        if (!APPROVAL_REQUIRED_LEVELS.contains(threatLevel)) return;

        // 같은 표적이 쿨다운 내 반복 분석/폴링으로 여러 번 HIGH/CRITICAL로 잡혀도
        // 이미 대기 중인 승인 요청이 있으면 중복 생성하지 않는다.
        boolean alreadyPending = repository
            .findFirstByTargetIdAndStatusOrderByRequestedAtDesc(targetId, "PENDING")
            .isPresent();
        if (alreadyPending) return;

        ThreatApproval approval = ThreatApproval.builder()
            .targetId(targetId)
            .targetType(targetType)
            .threatLevel(threatLevel)
            .sitrep(sitrep)
            .build();
        repository.save(approval);

        log.info("[ThreatApproval] 승인 요청 생성: targetId={}, threatLevel={}", targetId, threatLevel);
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

        approval.decide(decision, request.getDecidedBy(), request.getReason());
        log.info("[ThreatApproval] 승인 결정: id={}, targetId={}, decision={}, decidedBy={}",
            id, approval.getTargetId(), decision, request.getDecidedBy());

        ThreatApprovalDto.Response response = ThreatApprovalDto.Response.from(approval);
        messagingTemplate.convertAndSend("/topic/approvals", response);
        return response;
    }
}
