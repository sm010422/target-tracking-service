# 관측성 (Micrometer + Prometheus)

## 요약: 지금 뭐가 떠 있고 뭐가 안 떠 있나

**Prometheus 서버는 클러스터에 안 떠 있다.** 한 게 정확히는:

1. 앱(`target-tracking-service`)에 `micrometer-registry-prometheus` 라이브러리를 추가
2. 그러면 Spring Boot Actuator가 `GET /actuator/prometheus`라는 엔드포인트를 하나 만들어준다 — 요청이 들어올 때마다 그 순간까지 앱 안에 누적된 지표(Counter/Timer 값들)를 Prometheus가 읽을 수 있는 텍스트 포맷으로 즉석에서 렌더링해서 응답
3. 이 엔드포인트를 실제로 주기적으로 긁어가서(scrape) 시계열로 저장하고, 쿼리하고, 그래프로 보여주는 건 원래 Prometheus 서버(+Grafana)가 하는 일인데 **그 서버 자체를 새로 안 만들었다**

비유하면: "체온계는 몸에 붙여놨는데, 그 값을 30분마다 기록해서 그래프로 보여주는 간호사(Prometheus)는 아직 안 고용한" 상태다. `curl /actuator/prometheus`로 지금 이 순간의 숫자는 언제든 볼 수 있지만, "어제보다 latency가 늘었나?" 같은 걸 보려면 누군가(Prometheus)가 그 값을 주기적으로 수집해서 쌓아뒀어야 한다 — 지금은 안 쌓이고, 매번 curl한 순간의 스냅샷만 보인다.

**왜 이렇게 했는지는 아래 "왜 Prometheus 서버/Grafana를 클러스터에 새로 안 올렸나" 참고.**

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
