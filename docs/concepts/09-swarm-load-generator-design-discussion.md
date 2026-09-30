# 개념 정리 — "서버다운 부하"를 어떻게 만들 것인가 (설계→구현→배포 중 장애까지)

지금까지는 외부 데이터(ADS-B/AIS)를 우리가 끌어오는 쪽이라, 정작 이 서버가 "요청을 받아 처리하는 서버"로서 부하를 받는 일이 거의 없었다. 이걸 개선하고 싶다는 논의에서 시작해서, DMZ 드론 스웜 시나리오를 구현·배포하고, 그 과정에서 실제로 클러스터 자원 한계를 건드린 장애까지 겪은 전체 기록.

## 1. 처음 생각한 방향 — 동시 접속자 부하 테스트, 그리고 기각한 이유

처음엔 k6 같은 도구로 WebSocket/REST 동시 접속자를 시뮬레이션해서 Grafana로 관찰하는 방향을 제안했다. 그럴듯해 보였지만, 다시 생각해보니 이 프로젝트의 서사와 안 맞는다는 지적을 받았다:

> "이 프로젝트에서 서버가 부하를 받는 일이 거의 없다는 게 좀 아쉬운데? 대규모 데이터 처리 같은 느낌은 될 수 있지 않을까"

맞는 지적이었다. C4I(지휘통제) 시스템은 "동시 접속자가 몇 명이냐"로 부하가 걸리는 서비스가 아니다 — 실제 C4I 시스템의 부하는 **센서가 한 번에 얼마나 많은 표적을 쏟아내느냐**다. 사용자가 적은 게 이 도메인에서는 정상이고, 오히려 "동시 접속자 100명"을 억지로 만드는 게 서사에 안 맞는 이야기였다.

## 2. 방향 전환 — 합성 대량 표적 생성기 (드론 스웜/포화 공격 시나리오)

그래서 방향을 바꿨다: 외부 API(adsb.fi/aisstream.io)는 레이트리밋이 있어서 그쪽으로 부하를 키우는 건 애초에 이용약관 위반이 되고, 대신 **우리가 통제할 수 있는 합성 표적 생성기**로 대량의 이벤트를 Kafka에 쏟아부어서 파이프라인 자체를 시험하는 쪽이 낫다.

이렇게 하면 시험 대상이 이미 만들어둔 전체 파이프라인이 된다:
- Kafka 파티션/컨슈머 처리량
- PostgreSQL 쓰기 처리량
- pgvector 유사도 검색
- `07-gemini-quota-incident-and-on-demand-ai-analysis.md`에서 만든 AI 분석 게이팅(HIGH/CRITICAL만 분석)이 실제 포화 상황에서 의도대로 버티는지

그리고 Prometheus/Grafana(이미 구축됨)로 "초당 N표적 유입 시 컨슈머 랙/DB 쓰기 지연"을 그래프로 보여줄 수 있어서, 포트폴리오 그림으로도 부하 테스트 스크린샷 하나보다 훨씬 설득력 있다.

## 3. 기존 `DroneSimulator`를 그대로 못 쓰는 이유 — 순간이동 문제

대량 생성기를 만들려고 기존 `DroneSimulator`(`src/main/java/com/c4i/tracking/simulator/DroneSimulator.java`)를 보니, 규모만 다를 뿐 그대로 확장하면 안 되는 근본적인 문제가 있었다.

```java
public void simulate(int rounds) {
    for (int i = 0; i < rounds; i++) {
        DRONE_IDS.forEach(droneId -> {
            TargetEvent event = TargetEvent.builder()
                    .targetId(droneId)
                    .latitude(34.0 + random.nextDouble() * 4.0)   // 매 라운드 완전 새로 뽑음
                    .longitude(126.0 + random.nextDouble() * 4.0) // 이전 위치와 무관
                    ...
```

매 라운드 좌표를 이전 위치와 무관하게 `random.nextDouble()`로 완전히 새로 뽑는다 — 즉 이동이 아니라 **순간이동(teleport)**이다. 이 문제는 사실 처음이 아니다. `04-adsb-fi-live-feed-integration.md`에서 실제 ADS-B 데이터를 붙인 이유 중 하나가 정확히 이거였다:

> "매 라운드 완전 랜덤 좌표라 궤적이 물리적으로 말이 안 되던 문제(실제 항공기는 진짜 물리 법칙에 따라 이어지는 궤적을 가지니까)"

대량 생성기를 이 방식 그대로 수천 배 복제하면, 화면에는 수천 개의 점이 매 틱마다 무작위로 깜빡이는 것처럼 보일 뿐 "스웜이 접근해온다"는 그림이 안 나온다. 최근 프론트엔드에서 마커 보간(`useInterpolatedTargets`)과 궤적 스무딩(`smoothPath`)까지 다듬어놓은 게 있는데, 순간이동 데이터로는 그 작업이 무의미해진다.

## 4. 필요한 수정 방향 — 표적별 상태 유지 + dead reckoning

순간이동이 아니라 진짜 이동처럼 보이려면, 표적마다 **상태(위치·방위각·속도)를 틱 사이에 유지**하면서 매 틱 그 방향으로 실제 전진시켜야 한다 — 흔히 말하는 dead reckoning.

- AIS/ADS-B 통합에서 이미 쓴 haversine 공식은 "두 점 사이 거리"를 구하는 데 썼는데, 이번엔 반대 방향으로 뒤집어서 "현재 위치 + 방위각 + (속도 × dt)"로 **다음 위치를 계산**하는 데 쓰면 된다(destination point formula — haversine 거리 공식과 짝을 이루는, 방위각과 거리로 도착점을 구하는 공식).
- 표적 수가 많아질 걸 감안하면, 매 틱마다 랜덤 좌표를 새로 뽑는 지금 구조 대신 표적별 상태를 어딘가(메모리 Map, 혹은 Redis — 이미 클러스터에 있음)에 유지하는 구조로 바뀌어야 한다.
- 약간의 랜덤 방위각 드리프트를 주면 완전 직선 비행보다 자연스러워 보일 것(다만 이건 시각적 디테일이라 우선순위는 낮음).

## 5. 실제로 정한 것 — `DmzSwarmSimulator` 구현

`src/main/java/com/c4i/tracking/simulator/DmzSwarmSimulator.java`로 구현했다. 설계 논의에서 열어뒀던 질문들은 이렇게 정리됐다:

- **표적 수/지속시간**: API 파라미터로 뺐다(`count` 기본 20·최대 200, `durationSec` 기본 90·최대 300) — 클러스터 자원을 보면서 호출 시점에 단계적으로 올릴 수 있게. 고정값으로 박아두지 않은 이유는 바로 이 문서 7번 항목(장애)에서 드러난다.
- **상태 유지 위치**: 인메모리로 결정. Redis도 고려했지만, 시나리오가 최대 300초짜리 유한 이벤트라 파드 재시작으로 상태가 날아가는 게 실질적 문제가 안 되고, Redis 직렬화 왕복 비용을 붙일 이유가 없었다.
- **API 분리**: 기존 `SimulatorController`(`/api/simulator/*`)에 `/swarm` 엔드포인트로 얹었다 — 새 컨트롤러를 만들 만큼 성격이 다르지 않다고 판단.
- **이동 공식**: destination point formula(위치+방위각+거리 → 다음 위치)를 `DmzSwarmSimulator.destinationPoint()`로 순수 함수 분리해서, 스케줄러 없이 단위 테스트 가능하게 했다(`DmzSwarmSimulatorTest`, 5개 케이스 — 정남향/정동향 이동, 거리 비례, 제자리, 중복 실행 방지).
- **스폰 위치**: 철원-연천 축선 인근(38.20~38.30N, 127.05~127.35E, 정확한 군사분계선 좌표가 아니라 상징적 선택), 기본 방위 180도(정남)에서 ±20도 흩어지게 스폰해서 대형을 이룬 것처럼 보이게 했다.
- **"상시 생성기 아님" 요구사항**: `AtomicBoolean running`으로 중복 실행을 막고, 틱 카운트가 `durationSec / 2초`에 도달하면 스스로 `ScheduledFuture`를 취소하고 종료한다 — 버튼을 눌러야 시작되는 유한 이벤트.

프론트엔드(`c4i-dashboard-frontend`)에는 기존 "▶ 시뮬레이션 실행" 옆에 "🚁 DMZ 스웜" 버튼을 추가해서 `POST /api/simulator/swarm?count=20&durationSec=90`을 호출하게 했다.

## 6. 배포 후 실측 검증

배포하고 나서 실제로 남하하는지 API로 직접 확인했다:

```
DMZ-SWARM-01 38.21054 127.24930 ...
DMZ-SWARM-01 38.21023 127.24940 ...  (2초 뒤)
DMZ-SWARM-01 38.20991 127.24950 ...  (또 2초 뒤)
```

위도가 틱마다 일관되게 감소(남하) — `DroneSimulator`처럼 매번 무관한 랜덤 좌표가 아니라 실제로 이전 위치에서 이어지는 궤적이라는 걸 숫자로 확인했다. 브라우저에서도 DMZ 라인 바로 아래에 남쪽을 향한 삼각형 마커가 떴고 콘솔 에러도 없었다.

## 7. 장애 — 스웜 테스트가 다른 파드까지 Unhealthy로 만든 사건 (2026-09-30)

검증 직후 Headlamp에서 이벤트 7건이 Unhealthy로 떴다 — `prometheus`, `postgres`, `kube-state-metrics`, `target-tracking-service`(신·구 파드 둘 다) 전부 liveness/readiness probe 타임아웃.

**진단**: `kubectl get pods -o wide`로 배치를 보니 Unhealthy로 뜬 파드가 전부 **같은 노드(`k3s-worker1`)**에 몰려 있었다. 이 노드는 `Headlamp-Kubernetes-Dashboard.md`에서 이미 "여유 ~400MB" 수준으로 가장 빠듯하다고 확인했던 곳인데(당시 측정 61~72%), 지금은 81%까지 올라가 있었다. 스웜 테스트를 두 번 연달아 돌리면서(curl로 10대, 버튼으로 20대) `target-tracking-service`에 순간 CPU/메모리 부하가 튀었고, **같은 노드를 쓰는 다른 파드들까지 kubelet의 health check 응답이 늦어져서** 무더기로 Unhealthy로 잡혔다 — 개별 파드 버그가 아니라 노드 단위 자원 경합이었다.

**현재 상태**: 재시작 횟수(`RESTARTS`)는 전부 0 — 크래시 없이 자연 복구됨. `actuator/health/readiness`와 `/api/targets` 둘 다 재확인해서 `200 OK` 정상 확인.

**의미**: 이건 버그라기보다 "1번 항목에서 원했던 것"이 실제로 증명된 사건에 가깝다 — 대량 표적 생성기가 진짜로 클러스터 자원 한계를 건드릴 만큼의 부하를 만든다는 뜻. 동시에 "지금 기본값(20대)조차 가장 빠듯한 노드엔 부담"이라는 한계도 같이 드러났다.

**당장은 고치지 않기로 함** — 자연 복구됐고 데이터 유실도 없어서, 이번엔 원인만 기록해두고 다음에 스웜 규모를 더 키우려 할 때 참고하기로 했다. 나중에 손볼 후보:

1. `target-tracking-service` readiness probe `failureThreshold`를 순간 스파이크에 덜 민감하게 완화
2. 틱마다 드론 전체를 한 번에 Kafka로 쏘는 대신 살짝 스태거링해서 순간 부하 자체를 완화
3. `postgres`/`kube-state-metrics`처럼 무거운 걸 worker1에서 worker2로 옮겨 분산(단, worker2도 여유가 크지 않아 효과는 제한적일 수 있음)

## 관련 문서

- `docs/concepts/04-adsb-fi-live-feed-integration.md` — 순간이동 문제를 실제 데이터로 처음 해결했던 사례
- `docs/concepts/07-gemini-quota-incident-and-on-demand-ai-analysis.md` — 대량 유입 시 AI 분석을 게이팅하는 기존 로직, DRONE 타입이 타는 경로
- `docs/concepts/08-external-data-source-architecture.md` — 왜 외부 API 쪽으로 부하를 키우면 안 되는지(레이트리밋)의 배경
- `k3s-msa-infrastructure/docs/Headlamp-Kubernetes-Dashboard.md` — worker1 자원 여유를 처음 측정했던 문서, 이번 장애 진단에 그대로 쓰임
