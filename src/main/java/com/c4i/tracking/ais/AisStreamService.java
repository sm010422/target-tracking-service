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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * aisstream.io 실시간 AIS(선박 자동식별장치) WebSocket 피드를 기존 Kafka 파이프라인에
 * 흘려보낸다. AdsbFiPollingService(ADS-B, REST 폴링)와 같은 자리 -- 이 서비스가
 * TargetProducer로 발행하는 순간부터는 기존 파이프라인이 그대로 처리한다.
 *
 * REST가 아니라 WebSocket 전용 API라 별도 HTTP 클라이언트 라이브러리를 추가하는 대신
 * JDK 내장 java.net.http.WebSocket(Java 11+)을 그대로 쓴다.
 *
 * targetType="SHIP"으로 발행하므로 ThreatAnalysisService의 규칙 기반 등급(예: 이례적
 * 고속 SHIP → 경고 등급 상향)이 그대로 적용된다. AIRCRAFT 때와 같은 이유로(
 * ADS-B 민항기 폭주로 Gemini 무료 tier 쿼터를 다 써버렸던 사건, warrantsAiAnalysis 참고)
 * SHIP도 무조건 AI 분석하지 않고 HIGH/CRITICAL일 때만 분석하도록 이미 반영해뒀다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AisStreamService {

    private static final URI STREAM_URI = URI.create("wss://stream.aisstream.io/v0/stream");

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
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${ais.enabled:false}")
    private boolean enabled;

    @Value("${ais.api-key:}")
    private String apiKey;

    private volatile WebSocket webSocket;
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
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    private void connect() {
        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(STREAM_URI, new StreamListener())
            .thenAccept(ws -> {
                this.webSocket = ws;
                ws.sendText(buildSubscribeMessage(), true);
                log.info("[AisStream] 연결 및 구독 완료 (한국 연안 bounding box)");
            })
            .exceptionally(ex -> {
                log.warn("[AisStream] 연결 실패, {}초 후 재시도: {}", RECONNECT_DELAY_SEC, ex.getMessage());
                scheduleReconnect();
                return null;
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

    private class StreamListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("[AisStream] onOpen (listener) 호출됨");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            log.info("[AisStream] onText 호출됨: {}자, last={}", data.length(), last);
            buffer.append(data);
            webSocket.request(1);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[AisStream] 연결 종료 (code={}, reason={}), 재연결 예약", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[AisStream] 스트림 에러, 재연결 예약: {}", error.getMessage());
            scheduleReconnect();
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonNode message = objectMapper.readTree(raw);
            log.info("[AisStream] 메시지 수신: type={}", message.path("MessageType").asText("?"));
            TargetEvent event = parser.parse(message);
            if (event != null) {
                targetProducer.send(event);
            }
        } catch (Exception e) {
            log.warn("[AisStream] 메시지 처리 실패: {}", e.getMessage());
        }
    }
}
