package com.c4i.tracking.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TargetConsumer {

    @KafkaListener(topics = "target-tracking", groupId = "target-tracking-group")
    public void consume(TargetEvent event) {
        log.info("Kafka 수신: targetId={}, lat={}, lng={}, alt={}, speed={}",
                event.getTargetId(),
                event.getLatitude(),
                event.getLongitude(),
                event.getAltitude(),
                event.getSpeed());
        // 나중에 여기서 WebSocket으로 전송할 거예요
    }
}
