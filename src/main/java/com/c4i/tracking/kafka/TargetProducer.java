package com.c4i.tracking.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TargetProducer {

    private final KafkaTemplate<String, TargetEvent> kafkaTemplate;
    private static final String TOPIC = "target-tracking";

    public void send(TargetEvent event) {
        kafkaTemplate.send(TOPIC, event.getTargetId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 전송 실패: {}", ex.getMessage());
                    } else {
                        log.info("Kafka 전송 성공: targetId={}, offset={}",
                                event.getTargetId(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
