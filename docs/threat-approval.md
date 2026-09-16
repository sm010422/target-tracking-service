# Human-in-the-loop 승인 루프

## 왜 필요한가

기존 AI 위협 분석(`docs/ai-analysis.md`)은 SITREP을 "생성"하는 데서 끝났다. 실제 C4I 운용에서는 AI가 CRITICAL/HIGH로 판단했다고 그대로 자동 조치되지 않는다 — 담당자가 확인하고 승인/반려해야 하며, 그 결정 자체가 감사 기록으로 남아야 한다.

이 기능은 "AI 판단 → 사람 승인 → 결정 기록"이라는 루프를 완성한다. 단순히 분석 결과를 보여주는 챗봇/대시보드를 넘어, AI 판단이 실제 업무 프로세스(승인 워크플로)에 연결되는 걸 보여주는 게 목적이다.

## 동작 방식

```
ThreatAnalysisService.analyze(event)
        │
        ▼
   doAnalyze() -- 기존 RAG 분석 (규칙 기반 등급 + pgvector 검색 + Gemini SITREP)
        │
        ▼
ThreatApprovalService.createIfNeeded(targetId, targetType, threatLevel, sitrep)
        │
        ├─ threatLevel이 HIGH/CRITICAL이 아니면 아무 일도 안 함
        ├─ 같은 targetId로 이미 PENDING 승인 요청이 있으면 중복 생성 안 함
        └─ 신규 생성 시 DB 저장 + /topic/approvals로 WebSocket 브로드캐스트
```

- 트리거 지점은 `ThreatAnalysisService.analyze()` 하나뿐이다. REST(`/api/v1/threat-analysis/analyze`, 수동 버튼 클릭)와 Kafka(`analyzeAsync()`, 자동 분석)가 둘 다 내부적으로 `analyze()`를 거치므로 별도 분기 없이 두 경로 모두 커버된다.
- `GEMINI_API_KEY` 미설정 상태(AI 비활성화)에서도 규칙 기반 등급이 HIGH/CRITICAL이면 승인 요청이 생성된다 — 기존 Graceful Degradation 설계 원칙과 동일하게, AI 없이도 핵심 워크플로(사람이 고위험 표적을 검토)는 살아있어야 한다는 판단.

## 데이터 모델

`ThreatApproval` (`domain/approval/entity`)

| 필드 | 설명 |
|---|---|
| `targetId`, `targetType`, `threatLevel`, `sitrep` | 승인 요청 시점의 스냅샷 |
| `status` | `PENDING` → `APPROVED` / `REJECTED` |
| `requestedAt` | 생성 시각 |
| `decidedAt`, `decidedBy`, `decisionReason` | 결정 시각/결정자/사유 — 감사 로그 역할 |

## API

### 승인 대기 목록 조회
```http
GET /api/v1/threat-approvals              # status=PENDING 기본값
GET /api/v1/threat-approvals?status=ALL   # 전체(결정 완료 포함) 이력
```

### 승인/반려 결정
```http
POST /api/v1/threat-approvals/{id}/decide
Content-Type: application/json

{
  "decision": "APPROVED",
  "decidedBy": "operator1",
  "reason": "자산 방호 태세 전환 승인"
}
```

`decision`은 `APPROVED` / `REJECTED`만 허용되며, 그 외 값은 400으로 거부된다. 존재하지 않는 `id`는 404.

## WebSocket

`/topic/approvals` — 승인 요청 생성 시점과 결정 시점 양쪽에 동일한 `ThreatApprovalDto.Response` 페이로드를 브로드캐스트한다. 프론트엔드는 이 하나의 토픽만 구독해서 id로 upsert하면, 목록 조회 없이도 실시간으로 대기 목록/상태 변화를 반영할 수 있다 (`useApprovalSocket.ts`).

기존 `/topic/targets`(표적 실시간 전송)와 동일한 STOMP 브로커·엔드포인트(`/ws`)를 공유하며, 토픽만 분리했다.

## 프론트엔드

`c4i-dashboard-frontend`의 `ApprovalPanel.tsx` — 대시보드 상단 "🛡️ 승인 대기" 버튼으로 토글되는 좌측 패널. 대기 중인 항목마다 SITREP, 위협 등급, 승인/반려 버튼과 결정 사유 입력란을 보여준다. 승인 대기 건수는 버튼에 배지로 항상 노출된다.

## 다음 확장 아이디어

- 결정 로그(`decidedBy`/`decisionReason`)를 평가 데이터셋으로 축적해, "사람이 실제로 반려한 케이스"를 RAGAS 같은 평가 파이프라인의 회귀 테스트 케이스로 재사용
- 승인/반려 비율, 평균 결정 소요 시간 같은 지표를 Prometheus/Grafana로 노출 (관측성 강화 방향과 연결)
