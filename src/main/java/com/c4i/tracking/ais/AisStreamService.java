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
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * aisstream.io 실시간 AIS(선박 자동식별장치) WebSocket 피드를 기존 Kafka 파이프라인에
 * 흘려보낸다. AdsbFiPollingService(ADS-B, REST 폴링)와 같은 자리 -- 이 서비스가
 * TargetProducer로 발행하는 순간부터는 기존 파이프라인이 그대로 처리한다.
 *
 * 처음엔 JDK 내장 java.net.http.WebSocket + onText만으로 구현했는데 아무 메시지도
 * 안 들어왔다. Spring StandardWebSocketClient + TextWebSocketHandler로 바꾼 뒤에야
 * 진짜 원인이 드러났다: 서버가 CloseStatus 1003("Binary messages not supported")으로
 * 매번 연결을 끊고 있었다 -- **aisstream.io는 JSON을 텍스트가 아니라 바이너리 프레임으로
 * 보낸다.** JDK 버전은 onText만 있고 onBinary를 안 다뤄서 기본 구현(조용히 버림)에
 * 흡수됐던 것이고, TextWebSocketHandler는 바이너리 프레임 자체를 거부하는 게 차이였다.
 * 지금은 handleBinaryMessage에서 UTF-8로 디코딩해서 처리한다.
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

    private class AisHandler extends AbstractWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            session.sendMessage(new TextMessage(buildSubscribeMessage()));
            log.info("[AisStream] 연결 및 구독 완료 (한국 연안 bounding box)");
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            AisStreamService.this.handleMessage(message.getPayload());
        }

        /**
         * aisstream.io는 JSON을 텍스트가 아니라 **바이너리 프레임**으로 보낸다(실측으로
         * 확인 -- TextWebSocketHandler를 썼을 때 서버가 CloseStatus 1003 "Binary messages
         * not supported"로 매번 연결을 끊었다). UTF-8로 디코딩하면 동일한 JSON 텍스트다.
         */
        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            String payload = StandardCharsets.UTF_8.decode(message.getPayload()).toString();
            AisStreamService.this.handleMessage(payload);
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
