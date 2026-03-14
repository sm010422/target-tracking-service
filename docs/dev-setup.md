# 🛠️ 로컬 개발 환경 설정

## 사전 요구사항
- Java 21
- Docker
- Tailscale (서버 간 통신)

## 환경변수 설정
```bash
export KAFKA_BOOTSTRAP_SERVERS=100.106.186.41:9092
export DB_URL=jdbc:postgresql://localhost:5432/defense_db
export DB_USERNAME=postgres
export DB_PASSWORD=password
```

## 실행 순서

### 1. Kafka 실행 (server3)
```bash
cd ~/kafka
docker compose up -d
```

### 2. PostgreSQL 실행 (server2 또는 로컬)
```bash
docker compose up -d postgres
```

### 3. Spring Boot 실행
```bash
./gradlew bootRun
```

## Kafka 토픽 확인
```bash
docker exec -it defense_kafka kafka-topics \
  --list \
  --bootstrap-server localhost:9092
```

## 메시지 모니터링
```bash
docker exec -it defense_kafka kafka-console-consumer \
  --topic target-tracking \
  --bootstrap-server localhost:9092\
  --from-beginning
