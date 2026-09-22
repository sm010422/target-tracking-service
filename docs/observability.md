# 관측성 (Micrometer + Prometheus)

## 노출되는 지표

`GET /actuator/prometheus` (기존 `/actuator/health`는 K8s 프로브가 이미 사용 중, 이번에 `prometheus`만 새로 노출).

| 지표 | 타입 | 라벨 | 의미 |
|---|---|---|---|
| `threat_ai_analysis_total` | Counter | `result` (disabled / success / failure) | AI 위협 분석 결과. `failure`가 늘면 Gemini 쿼터/네트워크 문제를 로그를 뒤지지 않고 바로 확인 가능 |
| `threat_ai_llm_call_duration_seconds` | Timer | - | Gemini SITREP 생성 호출(`chatModel.call`)만의 소요 시간 (pgvector 검색 시간은 미포함) |
| `threat_approval_requested_total` | Counter | `threatLevel` (HIGH / CRITICAL) | 생성된 승인 요청 수 |
| `threat_approval_decided_total` | Counter | `decision` (APPROVED / REJECTED) | 결정 완료된 승인 요청 수 |
| `threat_approval_time_to_decision_seconds` | Timer | - | 승인 요청 생성부터 사람이 실제로 결정하기까지 걸린 시간 — human-in-the-loop 루프([docs/threat-approval.md](./threat-approval.md))의 핵심 관측값 |

```bash
curl https://k3s-master.taildcdcee.ts.net/actuator/prometheus | grep threat_
```

## 왜 Prometheus 서버/Grafana를 클러스터에 새로 안 올렸나

k3s worker 노드가 이미 메모리 70%대, load average가 vCPU 수를 넘는 상태로 실측됐다(2026-09-16 기준). 여기에 Prometheus(TSDB)+Grafana 같은 상태 유지형 워크로드를 새로 얹는 건 리소스 여유가 없는 상태에서 무리한 선택이라 판단했다 — `threat-intel-ai-service/docs/observability.md`와 동일한 결론.

대신 앱이 Prometheus 포맷으로 스스로를 계측하는 데까지만 해뒀다. `curl /actuator/prometheus` 한 번으로 지표를 확인할 수 있고, 나중에 리소스 여유가 생기면 별도 Prometheus 인스턴스가 이 엔드포인트를 스크레이핑하도록 붙이기만 하면 된다.
