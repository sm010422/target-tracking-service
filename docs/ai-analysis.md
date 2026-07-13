# AI 위협 분석 시스템 (RAG + pgvector)

## 개요

실시간으로 감지된 표적(드론, 미사일, 항공기)에 대해 **RAG(Retrieval-Augmented Generation)** 기반 위협 분석을 수행하고, LLM이 전술 상황 보고서(SITREP)를 자동 생성합니다.

## 기술 스택

| 역할 | 기술 |
|------|------|
| LLM (SITREP 생성) | Google Gemini 2.5 Flash |
| 임베딩 모델 | Google gemini-embedding-001 (1536차원) |
| 벡터 DB | PostgreSQL + pgvector (HNSW 인덱스) |
| AI 프레임워크 | Spring AI 1.1.8 |
| 비동기 처리 | Spring @Async (aiAnalysisExecutor) |

## RAG 파이프라인

```
표적 이벤트 (Kafka)
        │
        ▼
① 자연어 설명문 구성
   "표적ID=DRONE-001, 유형=DRONE, 고도=45m, 속도=280km/h ..."
        │
        ▼
② pgvector 유사 위협 패턴 검색  ◄─── 위협 지식 베이스 (10개 패턴, 코사인 유사도)
   Top-K=3, 유사도 임계값=0.5
        │
        ▼
③ LLM 컨텍스트 구성
   [현재 표적] + [유사 패턴 3개] + [규칙 기반 위협 등급]
        │
        ▼
④ Gemini 2.5 Flash → SITREP 생성
   - 상황 요약
   - 위협 평가
   - 권고 조치 (3가지 이내)
        │
        ▼
⑤ 결과 로그 / REST API 응답
```

## 위협 지식 베이스

`ThreatKnowledgeInitializer`가 앱 시작 시 10가지 위협 패턴을 pgvector에 적재합니다.

| 표적 유형 | 패턴 | 위협 등급 |
|-----------|------|-----------|
| DRONE | 저속 저고도 순찰 (정찰) | MEDIUM |
| DRONE | 고속 저고도 직선 접근 (자폭) | CRITICAL |
| DRONE | 군집 분산 접근 (포화) | HIGH |
| DRONE | 지그재그 + GPS 이상 (전자전) | MEDIUM |
| MISSILE | 초저고도 지형 따라 비행 (순항) | CRITICAL |
| MISSILE | 극초고속 탄도 궤적 (탄도) | CRITICAL |
| AIRCRAFT | 고속 고고도 직선 비행 (민항/우군) | LOW |
| AIRCRAFT | 급격한 기동 (전술 전투기) | HIGH |
| DRONE | 야간 침투, 적외선 신호 약함 | HIGH |
| AIRCRAFT | 민항기 위장, 비정상 경로 | HIGH |

## 규칙 기반 위협 등급 (사전 평가)

LLM 호출 전에 규칙 기반으로 위협 등급을 먼저 산출합니다. AI 미설정 시에도 단독 동작합니다.

| 조건 | 등급 |
|------|------|
| 유형=MISSILE | CRITICAL |
| DRONE + 속도>250 + 고도<100m | CRITICAL |
| DRONE + 고도<50m | HIGH |
| AIRCRAFT + 속도>800 + 고도<500m | HIGH |
| 속도>200km/h | MEDIUM |
| 그 외 | LOW |

## API 엔드포인트

### 상태 확인

```http
GET /api/v1/threat-analysis/status
```

**응답 (AI 활성화 시)**
```json
{
  "aiEnabled": true,
  "message": "AI 위협 분석 활성화됨 (RAG + pgvector)"
}
```

**응답 (API 키 미설정 시)**
```json
{
  "aiEnabled": false,
  "message": "AI 비활성화 - export GEMINI_API_KEY=<your-key> 후 재시작 필요"
}
```

---

### 위협 분석 요청

```http
POST /api/v1/threat-analysis/analyze
Content-Type: application/json

{
  "targetId": "DRONE-001",
  "targetType": "DRONE",
  "latitude": 37.5,
  "longitude": 127.0,
  "altitude": 45.0,
  "speed": 280.0,
  "status": "DETECTED"
}
```

**응답 (AI 활성화 시)**
```json
{
  "targetId": "DRONE-001",
  "targetType": "DRONE",
  "threatLevel": "CRITICAL",
  "sitrep": "1. 상황 요약: DRONE-001이 고도 45m, 속도 280km/h로 고속 저고도 직선 접근 중...\n2. 위협 평가: 자폭 드론 패턴과 92% 유사...\n3. 권고 조치: ① 즉각 요격...",
  "similarPatterns": [
    "표적유형: DRONE, 행동패턴: 고속(250-400km/h) 초저고도(0-50m) ...",
    "..."
  ],
  "aiEnabled": true
}
```

## 설정 및 실행

### 1. API 키 설정

[Google AI Studio](https://aistudio.google.com/apikey)에서 무료로 발급받을 수 있습니다. `.env` 파일에 추가하면 `docker compose`가 자동으로 읽습니다.

```bash
echo "GEMINI_API_KEY=AIza..." >> .env
```

### 2. 전체 스택 실행

```bash
docker compose up -d --build
```

Postgres(pgvector)/Redis/Kafka/Zookeeper/앱까지 한 번에 기동됩니다.  
pgvector/pgvector:pg16 이미지가 vector extension을 포함하고,
`spring.ai.vectorstore.pgvector.initialize-schema: true` 옵션이 schema와 HNSW 인덱스를 자동 생성합니다.

> 로컬에서 앱만 따로 핫리로드하며 개발하려면 인프라만 띄우고(`docker compose up -d postgres redis kafka zookeeper`),
> `export GEMINI_API_KEY=...` 후 `./gradlew bootRun`으로 실행하세요.

API 키 설정 시 시작 로그에서 지식 베이스 초기화 확인:
```
[ThreatAI] 위협 지식 베이스 초기화 시작 (10 개 패턴)
[ThreatAI] 위협 지식 베이스 초기화 완료
```

API 키 미설정 시:
```
[ThreatAI] GEMINI_API_KEY 미설정 - 위협 지식 베이스 초기화 건너뜀.
```

## 아키텍처 설계 결정

### pgvector를 선택한 이유

- PostgreSQL을 이미 사용 중이라 별도 벡터 DB(Pinecone, Weaviate 등) 없이 extension 추가만으로 도입 가능
- HNSW(Hierarchical Navigable Small World) 인덱스로 밀리초 단위 ANN 검색
- Spring AI PgVectorStore가 schema 자동 생성, 트랜잭션 통합 지원

### 비동기 분석 설계

Kafka Consumer 스레드가 AI 분석을 기다리지 않도록 `@Async`로 분리했습니다.  
Gemini API 응답 시간(~1-3초)이 WebSocket 실시간 전송(~10ms)에 영향을 주지 않습니다.

```
Kafka Consumer Thread          AI Analysis Thread (aiAnalysisExecutor)
       │                                │
       ├─ DB 저장                       │
       ├─ WebSocket 전송 (즉각)         │
       └─ analyzeAsync() 호출 ─────────►├─ pgvector 검색
                                        ├─ LLM 호출
                                        └─ 로그 출력
```
