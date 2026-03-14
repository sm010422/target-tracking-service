# 🏗️ 시스템 아키텍처

## 전체 흐름
```
DroneSimulator (1초마다 좌표 생성)
        ↓
TargetProducer
        ↓
Kafka Topic: target-tracking (server3 - 100.106.186.41:9092)
        ↓
TargetConsumer
        ↓
[WebSocket - 구현 예정] → 브라우저 대시보드
        ↓
PostgreSQL (server2 - 저장)
```

## 서버 구성

| 서버 | IP | 역할 | 실행 서비스 |
|------|-----|------|------------|
| server1 | 192.168.0.102 | K3s 마스터 | Redis, Grafana |
| server2 | 192.168.0.103 | K3s 워커 | Spring Boot, PostgreSQL |
| server3 | 192.168.0.104 | K3s 워커 | Kafka (KRaft), API Gateway |

## Kafka 구성

| 항목 | 값 |
|------|-----|
| 모드 | KRaft (Zookeeper 없음) |
| 토픽 | target-tracking |
| 파티션 | 3 |
| 브로커 | server3:9092 |

## 데이터 흐름 상세

### 1. 드론 시뮬레이터
- 1초마다 DRONE-001, 002, 003 좌표 생성
- 한반도 위도(34~38도) / 경도(126~130도) 범위
- Kafka Producer로 전송

### 2. Kafka 메시지 구조
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

### 3. 구현 예정
- TargetConsumer → WebSocket → 브라우저 실시간 전송
- PostgreSQL 저장 연동
- Grafana 모니터링
