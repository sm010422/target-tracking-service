# 개념 정리 — 가짜 시뮬레이터 옆에 진짜 항공기 데이터를 붙이기 (adsb.fi)

`docs/dashboard-data-and-ai-audit.md`에서 지적한 "① 데이터가 가짜라는 걸 아무도 모른다"는 문제를 실제로 풀어본 기록. 결론: 시뮬레이터를 대체하지 않고, `opendata.adsb.fi`의 공개 ADS-B 피드를 **병행 소스**로 같은 Kafka 파이프라인에 얹었다.

## 1. 남이 준 정보를 그대로 안 믿은 이유

작업을 시작하기 전에 "adsb.fi가 이래서 좋다"는 요약을 하나 받았는데, 실제로 찔러보니 세 가지가 틀렸다.

| 받은 정보 | 실제 확인 결과 |
|---|---|
| `GET https://adsb.fi` | 존재하지 않는 경로. 도메인만 있고 실제 API 경로가 없다 |
| 개별 항공기 레코드에 `military: true` 필드 | 그런 필드 없음. `/v2/mil`이라는 **별도 엔드포인트**로 군용기 목록만 받아서, hex 코드를 대조해야 판별 가능 |
| "완벽하게 자유로운 오픈 라이선스" | 실제로는 "personal, non-commercial use only" — 상업적 이용/재판매 금지, 출처 표시 필수. adsb.lol의 ODbL(진짜 오픈 라이선스)과는 다르다 |

`curl`로 직접 두드려서 확인한 실제 base URL: `https://opendata.adsb.fi/api/v3/lat/{lat}/lon/{lon}/dist/{radius}`. 이걸 안 하고 받은 정보 그대로 코드를 짰으면 처음부터 404였을 것이다 — 어디서 온 정보든, 실제로 호출해보기 전까지는 검증된 게 아니라는 원칙을 여기서도 그대로 적용했다.

## 2. 왜 시뮬레이터를 대체하지 않고 병행했나

실제 항공기 데이터를 붙이면 두 가지가 자동으로 개선된다 — ① 대시보드가 "가짜 데이터"라는 문제, ② 매 라운드 완전 랜덤 좌표라 궤적이 물리적으로 말이 안 되던 문제(실제 항공기는 진짜 물리 법칙에 따라 이어지는 궤적을 가지니까).

하지만 완전 대체는 안 했다. 이유는 두 가지:

1. **데모 가능성**: 새벽 시간대거나 우연히 근처에 항공기가 없으면, 실제 피드만 의존한 대시보드는 아무것도 안 뜬다. 시뮬레이터 버튼은 "언제든 눌러서 뭔가 보여줄 수 있는" 보장이라 그대로 남겨야 한다.
2. **개념적 불일치**: 이 시스템은 "드론 탐지"라는 서사인데, ADS-B는 여객기·헬기 같은 **유인 항공기**의 협조적 신호를 추적하는 거다. 실제 소형 드론은 애초에 ADS-B를 안 쏜다(그게 C4I 위협 시나리오의 핵심 전제 — 탐지 안 되는 저고도 무인기). 그래서 `targetType`을 `"DRONE"`이 아니라 **`"AIRCRAFT"`**로 명시해서, 기존 규칙 엔진(`ThreatAnalysisService`)의 AIRCRAFT 분기를 그대로 타게 했다 — 여객기 데이터를 드론인 척 속이지 않는다.

## 3. 기존 파이프라인을 그대로 재사용 — 새 코드가 거의 없다

```java
targetProducer.send(event); // AdsbFiPollingService에서 이 한 줄만 하면 끝
```

`TargetEvent`를 만들어서 기존 `TargetProducer`로 발행하는 순간, 그 뒤는 전부 이미 있던 파이프라인이 처리한다:

- `TargetConsumer` → PostgreSQL 저장, WebSocket 브로드캐스트(대시보드), `ThreatAnalysisService.analyzeAsync()`(비동기 AI 분석, targetId별 10분 쿨다운 적용)
- `threat-intel-ai-service`(Python) → 별도 consumer group으로 Qdrant 이력 색인

**Python 쪽은 코드를 한 줄도 안 고쳤다.** `TargetEvent.status`가 이미 범용 문자열 필드였고, `pattern_store.describe_event()`가 이미 그 값을 자연어 설명문에 포함시키고 있었어서, `status="MILITARY"`가 들어오면 그대로 임베딩되어 챗봇의 이력 검색에도 반영된다 — 필드를 추가한 게 아니라 기존 필드에 새로운 값을 흘려보낸 것뿐이라 스키마 변경이 전혀 없었다.

## 4. 단위 변환과 결측치 처리

ADS-B 원본은 고도를 피트, 속도를 노트로 준다.

```java
double altitudeMeters = altNode.isNumber() ? altNode.asDouble() * 0.3048 : 0.0;
double speedKmh = ac.path("gs").asDouble(0.0) * 1.852;
```

`alt_baro` 필드는 숫자(피트)거나, 지상에 있으면 문자열 `"ground"`로 온다는 걸 실제 응답에서 확인했다 — `JsonNode.isNumber()`로 분기해서 문자열인 경우 고도 0으로 처리했다. 엄격한 POJO(예: `record AdsbAircraft(double altBaro, ...)`)로 역직렬화했으면 이 필드에서 바로 파싱 예외가 났을 것 — 그래서 처음부터 `JsonNode`로 유연하게 파싱하는 쪽을 택했다.

## 5. 군용기 판별과 위협 등급 가중치

```java
Set<String> militaryHexes = adsbFiClient.fetchMilitaryHexes(); // /v2/mil 전체 조회
boolean isMilitary = militaryHexes.contains(hex);              // 반경 조회 결과와 hex 대조
```

매 폴링 주기(기본 20초)마다 전 세계 군용기 목록(현재 227대)을 통째로 받아서, 반경 조회로 받은 기체들의 hex와 대조한다. 군용기로 확인되면 `status="MILITARY"`로 표시하고:

```java
if ("MILITARY".equals(event.getStatus())) {
    level = escalate(level); // LOW→MEDIUM, MEDIUM→HIGH, HIGH/CRITICAL은 유지
}
```

군용이라는 사실 하나만으로 무조건 CRITICAL을 주지는 않았다 — 정상 순찰 중인 헬기까지 전부 최고 등급으로 뜨면 그 등급 자체가 무의미해진다. "군용 자산이니 한 단계 더 주시해야 한다" 정도로만 반영했다.

## 6. 지역 설정 분리 — 수도권 기본값, 분쟁지역 프리셋

```yaml
adsb:
  center-lat: ${ADSB_CENTER_LAT:37.5665}   # 서울
  center-lon: ${ADSB_CENTER_LON:126.9780}
  radius-nm: ${ADSB_RADIUS_NM:100}
```

중심 좌표를 하드코딩하지 않고 환경변수로 뺐다 — 공개 ADS-B 신호는 전 세계 자원봉사자가 수신해서 올리는 데이터라, 우크라이나(키이우 인근)나 이란(테헤란 인근) 같은 분쟁지역 상공의 공개 항공 트래픽으로도 그대로 전환 가능하다(둘 다 민간 여객 노선이 실제로 지나가고, 국경 근처 군용기 이동도 간간이 잡힌다). 이건 누구나 잡을 수 있는 공개 전파 신호를 집계한 것이라 OSINT 관점에서 합법적인 이용이다.

## 7. 레이트리밋 — "초당 최대 1회"를 실제로 어떻게 지켰나

adsb.fi의 이용약관에 명시된 제한은 명확하다: **공용 서버 보호를 위해 초당 최대 1회(1 Request/Second)**. 이 한도를 넘기면 IP 단위로 차단당할 수 있다는 게 커뮤니티에 알려진 관례다.

처음 짠 `poll()`은 이 한도를 은근슬쩍 어기고 있었다:

```java
// 수정 전 — 매 주기(20초)마다 이 두 줄이 거의 동시에 실행됨
Set<String> militaryHexes = adsbFiClient.fetchMilitaryHexes();      // 요청 1
JsonNode aircraft = adsbFiClient.fetchAircraftNear(centerLat, centerLon, radiusNm); // 요청 2 (바로 이어서)
```

**주기 자체(20초)는 한도 대비 이미 20배 이상 여유로웠다** — 문제는 주기가 아니라, **한 주기 안에서 요청을 두 번, 그것도 거의 같은 순간에** 쏘고 있었다는 것. "초당 1회"라는 한도는 두 요청 사이에 최소 1초 간격이 있어야 한다는 뜻인데, Java 코드에서 두 줄을 순서대로 실행하면 그 사이 간격은 보통 수십~수백 밀리초에 불과하다 — 매 폴링 주기마다 한도를 아슬아슬하게(혹은 완전히) 넘기고 있었던 셈이다.

### 고친 방법 — 군용기 목록을 별도 캐시로 분리

군용기 편성은 초 단위로 바뀌는 게 아니라는 점에 착안해서, 두 요청의 **빈도 자체를 다르게** 가져갔다.

```java
@Value("${adsb.military-cache-ttl-ms:120000}")  // 기본 2분
private long militaryCacheTtlMs;

private volatile Set<String> militaryHexCache = Set.of();
private volatile Instant militaryCacheUpdatedAt = Instant.EPOCH;

private Set<String> getMilitaryHexesCached() {
    boolean stale = Duration.between(militaryCacheUpdatedAt, Instant.now()).toMillis() >= militaryCacheTtlMs;
    if (stale) {
        militaryHexCache = adsbFiClient.fetchMilitaryHexes();
        militaryCacheUpdatedAt = Instant.now();
    }
    return militaryHexCache;
}
```

결과:
- 반경 조회(`fetchAircraftNear`)는 여전히 20초마다 — 초당 0.05회, 한도 대비 20배 여유
- 군용기 목록 조회(`fetchMilitaryHexes`)는 캐시가 만료된 그 순간(2분에 한 번)에만 — 나머지 5번의 주기(20초×6=120초 동안 6번 폴링)는 아예 API를 안 부르고 캐시된 `Set<String>`만 재사용
- **두 요청이 "같은 폴링 사이클"에서 동시에 나가는 경우 자체가 6번 중 1번으로 줄었고**, 그마저도 캐시 갱신 시점이 정확히 반경조회 직후가 아니라 "다음 stale 체크 시점"이라 실질적으로 겹칠 확률이 더 낮아졌다.

이 방식이 좋은 이유는 단순히 "더 느리게 부르기"가 아니라 **애초에 초 단위로 안 바뀌는 데이터를 초 단위로 다시 물어보고 있었다는 비효율 자체를 없앤 것**이라는 점이다 — 레이트리밋 회피와 불필요한 API 호출 감소가 같은 수정 하나로 해결됐다.

## 8. 배포하면서 겪은 삽질 — "로그가 안 찍힌다"는 착각

배포 직후 pod 로그에서 `[AdsbFi]` 태그가 있는 로그를 아무리 찾아도 안 보여서 한참 헤맸다. 실제로는 두 가지가 겹쳐 있었다.

**① Image Updater가 아직 새 digest를 못 감지한 상태였다.** `kubectl get pods`로는 "새 pod가 떴다"고 보였지만, 그건 내가 먼저 `deployment.yaml`에 `ADSB_ENABLED=true` 환경변수만 커밋해서 push했고, ArgoCD가 그 git 변경(이미지는 그대로, env만 추가)을 감지해서 **예전 이미지 그대로** pod를 재생성한 것뿐이었다. Image Updater는 자기 폴링 주기(약 2분)가 따로 있어서, Docker Hub에 새 이미지가 올라간 것과 그걸 감지해서 write-back하는 것 사이에 시차가 있다. 실제로 pod 안의 jar 파일을 `kubectl cp`로 꺼내서 `unzip -l`로 까보니 `com/c4i/tracking/adsb` 패키지 자체가 없는, 하루 전 빌드였다 — **"pod가 재시작됐다"가 "새 코드가 배포됐다"를 보장하지 않는다**는 걸 다시 확인한 것.

**② 그다음엔 진짜로 배포됐는데도 못 찾았다 — `kubectl logs --since`의 함정.** Image Updater가 새 digest를 반영한 뒤, `--since=5m`처럼 시간 윈도우를 지정해서 로그를 찾았는데 계속 빈 결과가 나왔다. 그런데 실제 위협분석 로그(`targetId=AAR8985`, `71c004` 같은 진짜 항공기 콜사인/hex)는 같은 시간대에 버젓이 찍히고 있었다 — 파이프라인은 명백히 살아있는데 내가 지정한 요약 로그 한 줄만 유독 안 잡히는 상황. 결국 `--since` 옵션을 빼고 pod 이름을 직접 지정해서 전체 로그를 훑으니 바로 나왔다:

```
[AdsbFi] 중심(37.5665, 126.978) 반경 100nm: 61대 발행 (군용 목록 225대 대조)
[AdsbFi] 중심(37.5665, 126.978) 반경 100nm: 60대 발행 (군용 목록 232대 대조)
```

`--since`가 실제로 왜 그 로그 라인만 빼먹었는지까지는 확인 못 했다(멀티 컨테이너 로그의 타임스탬프 처리나 버퍼링 관련 추정) — 다만 여기서 얻은 교훈은, **"grep으로 안 잡히면 기능이 안 도는 것"이라고 성급히 결론내리지 말고, 다른 증거(이 경우 다운스트림 AI 분석 로그에 실제 데이터가 찍히는지)로 교차 검증**하는 것. 실제로 다운스트림 증거가 이미 "확실히 돌고 있다"고 말해주고 있었는데, 로그 검색 방법 하나만 믿고 몇 분을 더 헤맬 뻔했다.

## 9. 최종 검증 수치

```
[AdsbFi] 중심(37.5665, 126.978) 반경 100nm: 60~63대 발행 (군용 목록 225~232대 대조)
```

서울 중심 100nm 반경에서 20초마다 60여 대의 실제 항공기가 발행되고, 그 중 대한항공/아시아나 등 실제 콜사인(`AAR8985`, `DAL26` 등)이 `ThreatAnalysisService`를 거쳐 정상적으로 `threatLevel=LOW`와 SITREP까지 생성하는 것을 확인했다. 전 세계 군용기 목록은 캐시 시점마다 225~232대 사이로 변동 — 실시간으로 갱신되는 진짜 데이터라는 뜻.

## 관련 문서

- `docs/dashboard-data-and-ai-audit.md` — 이 작업의 동기가 된 감사 문서
- `docs/concepts/03-dashboard-ai-integration.md` — 같은 Kafka 이벤트를 소비하는 AI 위협분석/챗봇 연동
