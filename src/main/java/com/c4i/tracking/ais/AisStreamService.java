package com.c4i.tracking.ais;

import com.c4i.tracking.kafka.TargetEvent;
import com.c4i.tracking.kafka.TargetProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * aisstream.io 실시간 AIS(선박 자동식별장치) WebSocket 피드를 기존 Kafka 파이프라인에
 * 흘려보낸다. AdsbFiPollingService(ADS-B, REST 폴링)와 같은 자리 -- 이 서비스가
 * TargetProducer로 발행하는 순간부터는 기존 파이프라인이 그대로 처리한다.
 *
 * 처음엔 JDK 내장 java.net.http.WebSocket으로 구현했는데, 연결·구독(onOpen, sendText)은
 * 성공하고 로그도 남는데 서버가 보내는 메시지에 대해 onText 콜백이 단 한 번도 호출되지
 * 않는 문제를 실측으로 확인했다(같은 API 키/구독 메시지로 만든 별도 Node.js 스크립트는
 * 1초 안에 정상 수신). aisstream.io가 SubscriptionConfirmation에서 CompressionEnabled:true를
 * 명시하는 걸 보면 permessage-deflate 확장 관련 호환성 문제로 추정되나 근본 원인을 더
 * 파고들기보다, 이 앱이 대시보드 STOMP 브로드캐스트로 이미 실전 검증된 Spring
 * StandardWebSocketClient(Tomcat Jakarta WebSocket 구현)로 교체하는 쪽을 택했다.
 *
 * targetType="SHIP"으로 발행하므로 ThreatAnalysisService의 규칙 기반 등급이 그대로
 * 적용된다. AIRCRAFT 폭주로 Gemini 쿼터를 다 써버렸던 사건과 같은 이유로 SHIP도
 * warrantsAiAnalysis()에서 무조건 분석하지 않고 HIGH/CRITICAL일 때만 분석한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AisStreamService {

    private static final String STREAM_URL = "wss://stream.aisstream.io/v0/stream";

    // 한국 연안(서해/남해/동해 인접) 대략적인 커버리지 -- adsb.fi KOREA 권역과 같은
    // 수도권 기준점(37.5665, 126.9780)을 포함하도록 잡았다.
    private static final double MIN_LAT = 33.0;
    private static final double MIN_LON = 124.5;
    private static final double MAX_LAT = 38.5;
    private static final double MAX_LON = 130.0;

    private static final long RECONNECT_DELAY_SEC = 10;

    private final TargetProducer targetProducer;
    private final AisMessageParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final WebSocketClient webSocketClient = new StandardWebSocketClient();

    @Value("${ais.enabled:false}")
    private boolean enabled;

    @Value("${ais.api-key:}")
    private String apiKey;

    private volatile boolean shuttingDown = false;

    @PostConstruct
    public void start() {
        if (!enabled) return;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AisStream] AIS_STREAM_API_KEY 미설정, 연결을 생략합니다.");
            return;
        }
        connect();
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        reconnectExecutor.shutdownNow();
    }

    private void connect() {
        webSocketClient.execute(new AisHandler(), STREAM_URL)
            .whenComplete((session, ex) -> {
                if (ex != null) {
                    log.warn("[AisStream] 연결 실패, {}초 후 재시도: {}", RECONNECT_DELAY_SEC, ex.getMessage());
                    scheduleReconnect();
                }
            });
    }

    private void scheduleReconnect() {
        if (shuttingDown) return;
        reconnectExecutor.schedule(this::connect, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
    }

    private String buildSubscribeMessage() {
        // aisstream.io 구독 메시지 포맷: BoundingBoxes는 [[[minLat,minLon],[maxLat,maxLon]]].
        // PositionReport만 필터링해서 받는다 -- 다른 메시지 타입(정적 선박 정보 등)은
        // 지금 파이프라인(TargetEvent: 위치/속도/방향)에 필요 없다.
        return """
            {"APIKey":"%s","BoundingBoxes":[[[%s,%s],[%s,%s]]],"FilterMessageTypes":["PositionReport"]}
            """.formatted(apiKey, MIN_LAT, MIN_LON, MAX_LAT, MAX_LON).strip();
    }

    private class AisHandler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            session.sendMessage(new TextMessage(buildSubscribeMessage()));
            log.info("[AisStream] 연결 및 구독 완료 (한국 연안 bounding box)");
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            // TextWebSocketHandler에 이미 handleMessage(session, WebSocketMessage)가 상속돼
            // 있어서, 이름이 같은 바깥 클래스 메서드를 호출하려면 명시적으로 한정해야 한다.
            AisStreamService.this.handleMessage(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            log.warn("[AisStream] 연결 종료 ({}), 재연결 예약", status);
            scheduleReconnect();
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("[AisStream] 스트림 에러, 재연결 예약: {}", exception.getMessage());
            scheduleReconnect();
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonNode message = objectMapper.readTree(raw);
            TargetEvent event = parser.parse(message);
            if (event != null) {
                targetProducer.send(event);
            }
        } catch (Exception e) {
            log.warn("[AisStream] 메시지 처리 실패: {}", e.getMessage());
        }
    }
}
