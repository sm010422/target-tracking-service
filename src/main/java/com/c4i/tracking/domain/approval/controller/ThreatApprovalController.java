package com.c4i.tracking.domain.approval.controller;

import com.c4i.tracking.domain.approval.dto.ThreatApprovalDto;
import com.c4i.tracking.domain.approval.service.ThreatApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/threat-approvals")
@RequiredArgsConstructor
public class ThreatApprovalController {

    private final ThreatApprovalService threatApprovalService;

    /**
     * GET /api/v1/threat-approvals?status=PENDING (기본값)
     * GET /api/v1/threat-approvals?status=ALL
     */
    @GetMapping
    public ResponseEntity<List<ThreatApprovalDto.Response>> list(
            @RequestParam(required = false, defaultValue = "PENDING") String status) {
        List<ThreatApprovalDto.Response> result = "ALL".equalsIgnoreCase(status)
            ? threatApprovalService.listAll()
            : threatApprovalService.listPending();
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/v1/threat-approvals/{id}/decide
     * { "decision": "APPROVED" | "REJECTED", "decidedBy": "operator1", "reason": "..." }
     */
    @PostMapping("/{id}/decide")
    public ResponseEntity<ThreatApprovalDto.Response> decide(
            @PathVariable Long id,
            @RequestBody ThreatApprovalDto.DecisionRequest request) {
        return ResponseEntity.ok(threatApprovalService.decide(id, request));
    }
}
