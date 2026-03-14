# 📡 WebSocket 실시간 통신

## 개요

Kafka Consumer가 수신한 드론 좌표 데이터를 WebSocket(STOMP)으로
브라우저 대시보드에 실시간 전송합니다.

## 흐름
```
Kafka Consumer
      ↓
PostgreSQL 저장
      ↓
WebSocket(/topic/targets)
      ↓
브라우저 대시보드 (실시간 테이블 업데이트)
```

## 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `/ws` | WebSocket 연결 (SockJS) |
| `/topic/targets` | 드론 좌표 구독 |
| `/app/*` | 클라이언트 → 서버 전송 prefix |

## 메시지 구조
```json
{
  "targetId": "DRONE-001",
  "targetType": "DRONE",
  "latitude": 35.49,
  "longitude": 127.83,
  "altitude": 423.6,
  "speed": 226.6,
  "status": "DETECTED"
}
```

## 대시보드

`http://localhost:8080` 접속 시 실시간 전술 대시보드 확인 가능

- 1초마다 DRONE-001, 002, 003 좌표 업데이트
- 군용 콘솔 스타일 UI
