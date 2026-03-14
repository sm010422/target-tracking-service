package com.c4i.tracking.kafka;

import com.c4i.tracking.domain.target.service.TargetService;
import com.c4i.tracking.domain.target.dto.TargetDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TargetConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final TargetService targetService;

    @KafkaListener(topics = "target-tracking", groupId = "target-tracking-group")
    public void consume(TargetEvent event) {
        log.info("Kafka 수신: targetId={}, lat={}, lng={}",
                event.getTargetId(),
                event.getLatitude(),
                event.getLongitude());

        // 1. PostgreSQL 저장
        TargetDto.Request request = TargetDto.Request.builder()
                .targetId(event.getTargetId())
                .targetType(event.getTargetType())
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .altitude(event.getAltitude())
                .speed(event.getSpeed())
                .status(event.getStatus())
                .build();

        targetService.saveTarget(request);

        // 2. WebSocket으로 실시간 전송
        messagingTemplate.convertAndSend("/topic/targets", event);
        log.info("WebSocket 전송: targetId={}", event.getTargetId());
    }
}
