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
import java.time.Duration;
import java.time.Instant;
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
 *
 * 운영 중 "좀비 연결" 버그를 하나 겪었다 -- afterConnectionClosed/handleTransportError
 * 둘 다 안 불린 채로, JVM 입장에서는 세션이 여전히 "열려" 있는데 16시간 넘게 메시지가
 * 한 건도 안 들어온 상태가 됐다(선박 데이터 0건, [AisStream] 로그도 0건). TCP 연결이
 * close 프레임 없이 죽는 경우(중간 프록시/NAT idle timeout 등) Java WebSocket 클라이언트가
 * 이걸 스스로 감지할 방법이 없어서 생기는 문제 -- 재연결 로직 자체는 멀쩡했지만 애초에
 * "연결이 죽었다"는 신호가 한 번도 발생하지 않아 트리거될 일이 없었다. 그래서 마지막
 * 메시지 수신 시각을 추적하는 워치독을 추가했다: 일정 시간 이상 조용하면 세션을
 * 죽었다고 간주하고 강제로 재연결한다.
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

    // 이 권역에서 정상 상태라면 몇 분 안에 최소 한 건은 PositionReport가 들어온다 --
    // 그보다 길게 조용하면 정상 트래픽 공백이 아니라 연결이 죽었다고 판단한다.
    private static final long IDLE_TIMEOUT_SEC = 120;
    private static final long WATCHDOG_INTERVAL_SEC = 30;

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

    // 현재 유효한 세션 -- 워치독이 강제로 갈아치운 뒤에는 옛 세션의 콜백(뒤늦게 불리더라도)이
    // 중복 재연결을 예약하지 못하도록 신원 비교(==)에 쓴다.
    private volatile WebSocketSession session;
    private volatile Instant lastMessageAt = Instant.now();

    @PostConstruct
    public void start() {
        if (!enabled) return;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AisStream] AIS_STREAM_API_KEY 미설정, 연결을 생략합니다.");
            return;
        }
        connect();
        reconnectExecutor.scheduleAtFixedRate(
            this::checkIdle, WATCHDOG_INTERVAL_SEC, WATCHDOG_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        reconnectExecutor.shutdownNow();
    }

    private void connect() {
        webSocketClient.execute(new AisHandler(), STREAM_URL)
            .whenComplete((newSession, ex) -> {
                if (ex != null) {
                    log.warn("[AisStream] 연결 실패, {}초 후 재시도: {}", RECONNECT_DELAY_SEC, ex.getMessage());
                    scheduleReconnect();
                } else {
                    session = newSession;
                    lastMessageAt = Instant.now();
                }
            });
    }

    /**
     * 정상적인 close/error 콜백이 아예 안 불리는 좀비 연결을 잡아낸다. 마지막 메시지
     * 수신 후 IDLE_TIMEOUT_SEC 넘게 조용하면 현재 세션을 버리고 새로 연결한다 -- 옛
     * 세션은 session 필드에서 먼저 떼어낸 뒤 close()하므로, 그 세션의 콜백이 나중에
     * 뒤늦게 불려도(신원이 이미 안 맞아서) 중복 재연결로 이어지지 않는다.
     */
    private void checkIdle() {
        if (shuttingDown) return;
        WebSocketSession current = session;
        if (current == null) return; // 이미 재연결 대기 중 -- 워치독이 개입할 필요 없음

        long idleSec = Duration.between(lastMessageAt, Instant.now()).toSeconds();
        if (idleSec < IDLE_TIMEOUT_SEC) return;

        log.warn("[AisStream] {}초간 메시지 수신 없음 -- 좀비 연결로 판단, 강제 재연결", idleSec);
        session = null;
        try {
            current.close();
        } catch (Exception e) {
            // 이미 죽어있는 세션이라 close()도 실패할 수 있다 -- 무시하고 아래에서 새로 연결
        }
        connect();
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
        public void afterConnectionClosed(WebSocketSession closedSession, CloseStatus status) {
            log.warn("[AisStream] 연결 종료 ({}), 재연결 예약", status);
            // 워치독이 이미 이 세션을 버리고 새로 연결했다면(session != closedSession),
            // 여기서 또 재연결을 예약하면 중복 연결로 이어진다 -- 현재 세션일 때만 처리.
            if (closedSession == session) {
                session = null;
                scheduleReconnect();
            }
        }

        @Override
        public void handleTransportError(WebSocketSession errorSession, Throwable exception) {
            log.warn("[AisStream] 스트림 에러, 재연결 예약: {}", exception.getMessage());
            if (errorSession == session) {
                session = null;
                scheduleReconnect();
            }
        }
    }

    private void handleMessage(String raw) {
        lastMessageAt = Instant.now();
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
