package com.c4i.tracking.domain.target.controller;

import com.c4i.tracking.domain.target.dto.TargetDto;
import com.c4i.tracking.domain.target.service.TargetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/targets")
@RequiredArgsConstructor
public class TargetController {

    private final TargetService targetService;

    // 표적 데이터 수신 (센서 → 서버)
    @PostMapping
    public ResponseEntity<TargetDto.Response> receiveTarget(
            @RequestBody TargetDto.Request request) {
        return ResponseEntity.ok(targetService.saveTarget(request));
    }

    // 전체 표적 조회
    @GetMapping
    public ResponseEntity<List<TargetDto.Response>> getAllTargets() {
        return ResponseEntity.ok(targetService.getAllTargets());
    }

    // 상태별 표적 조회
    @GetMapping("/status/{status}")
    public ResponseEntity<List<TargetDto.Response>> getByStatus(
            @PathVariable String status) {
        return ResponseEntity.ok(targetService.getTargetsByStatus(status));
    }
}
