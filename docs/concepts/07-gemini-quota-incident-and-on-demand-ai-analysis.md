# 개념 정리 — Gemini 무료 tier 쿼터 소진 사고와 "버튼 눌렀을 때만" AI 호출로의 전환

대시보드에서 아무 표적이나 눌러도 "AI 위협 분석" 모달이 `HTTP 500`을 뱉는 걸 발견하고 원인을 추적한 기록. 결론적으로 배포 문제가 아니라 Gemini 무료 tier 쿼터를 실제로 다 써버린 거였고, 그 원인을 파고들어 보니 사용자가 버튼을 누르지 않아도 서버가 백그라운드에서 조용히 Gemini를 계속 호출하고 있었다.

## 1. 증상 — 아무 표적이나 눌러도 500

```json
{"timestamp":"...","status":500,"error":"Internal Server Error","message":"서버 내부 오류가 발생했습니다."}
```

pod 로그를 보니 원인은 명확했다:

```
com.google.genai.errors.ClientException: 429 . You exceeded your current quota...
* Quota exceeded for metric: generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 1000
Please retry in 46.576250243s.
```

`ThreatAnalysisService.analyze()`가 RAG 검색(`vectorStore.similaritySearch`)을 위해 Gemini 임베딩 API를 부르는데, 이게 무료 tier 일일 한도(1000건)를 넘겨서 429가 났고, 그걸 그대로 예외로 던지고 있었으니 사용자 입장에선 "그냥 안 되는 기능"으로 보였다.

## 2. Google AI Studio 비율 제한 페이지로 실측 확인

`aistudio.google.com/rate-limit`에서 지난 28일 모델별 사용량을 직접 확인했다:

| 모델 | RPD (일일 요청) |
|---|---|
| Gemini Embedding 1 | **1.01K / 1K — 초과** |
| Gemini 2.5 Flash | 32 / 20 — 초과 (예전에 갈아탄 모델) |
| Gemini 3.6 Flash | 24 / 20 — 초과 (예전에 갈아탄 모델) |
| Gemini 3.5 Flash Lite (현재 사용 모델) | 149 / 500 — 여유 있음 |

임베딩 모델만 한도를 넘겼고, 실제 채팅/SITREP 생성에 쓰는 모델(`gemini-flash-lite-latest` → Gemini 3.5 Flash Lite)은 아직 여유가 있었다. 즉 병목은 "AI가 응답을 못 만든 것"이 아니라 "RAG 검색 단계의 임베딩 호출"이었다.

화면 상단엔 "비율 제한에 도달했습니다. 결제를 설정하여 한도를 높이세요"라는 배너가 떴는데, 이 배너가 뜬다는 사실 자체가 이 프로젝트에 결제 수단이 연결돼 있지 않다는 증거이기도 하다 — 결제 계정이 없으면 물리적으로 과금될 방법이 없고, 한도를 넘으면 그냥 429로 막히는 걸로 끝난다. 실제로 "지출" 탭에서 금액이 $0인 것도 확인했다.

## 3. 응급 조치 — 수동 요청도 500 대신 우아하게 실패하도록

`analyze()`가 임베딩/LLM 호출 중 예외가 나면 그냥 던지던 걸, catch해서 규칙 기반 등급 + 안내 문구로 대체하도록 고쳤다:

```java
} catch (Exception e) {
    log.warn("[ThreatAI] LLM 호출 실패 (쿼터/네트워크), 규칙 기반 등급으로 대체: ...");
    return ThreatAnalysisDto.Response.builder()
        .threatLevel(ruleBasedLevel)
        .sitrep("⚠️ AI 분석 일시 실패 (Gemini API 요청량 제한 또는 일시 오류) — 규칙 기반 등급만 표시됩니다...")
        .aiEnabled(true)
        .build();
}
```

같은 요청으로 재현했더니 `HTTP 500` → `HTTP 200`으로 정상 응답하는 걸 확인했다. 이건 근본 해결이 아니라 "쿼터가 없을 때도 화면이 안 죽게" 하는 완화였다.

## 4. 진짜 원인 — 버튼을 안 눌러도 자동으로 소진되고 있었다

"방금 몇 번 안 눌렀는데 왜 벌써 쿼터가 찼지?"라는 의문에서 코드를 다시 봤다. `TargetConsumer.java`:

```java
@KafkaListener(topics = "target-tracking", groupId = "target-tracking-group")
public void consume(TargetEvent event) {
    ...
    threatAnalysisService.analyzeAsync(event);   // 모든 Kafka 메시지마다 무조건 호출
}
```

**Kafka로 들어오는 모든 표적 이벤트마다** (ADS-B가 20초마다 쏟아내는 항공기든, 시뮬레이터가 만드는 드론/미사일이든) 예외 없이 `analyzeAsync()`가 불린다. 그 안에서 실제 Gemini 호출 여부는 세 조건으로 걸러지는데:

```java
private boolean warrantsAiAnalysis(TargetEvent event) {
    if (!"AIRCRAFT".equals(event.getTargetType())) return true;   // DRONE/MISSILE은 무조건
    if ("MILITARY".equals(event.getStatus())) return true;         // 군용기는 무조건
    String level = calculateRuleBasedThreatLevel(event);
    return "HIGH".equals(level) || "CRITICAL".equals(level);
}
```

문제는 **군용기(status=MILITARY)가 반경 안에 계속 떠 있는 한, 쿨다운(당시 10분)마다 사용자 개입 없이 자동으로 재분석**된다는 점이었다. 대시보드의 "군용기만" 필터에 상시 4대 정도가 잡혀 있었으니, 이것만으로 하루 48회 이상(24h ÷ 10min × 4대) 백그라운드에서 조용히 Gemini를 불렀다는 계산이 나온다. 사용자가 버튼을 몇 번 눌렀는지와 무관하게, 이 자동 경로 하나만으로 무료 tier 임베딩 한도를 소진하기에 충분했다.

## 5. 근본 조치 — 자동분석을 기본 OFF로, 설정 플래그로 감싸기

두 가지 선택지를 고민했다:

- **완전 삭제**: 가장 단순하지만, "AI가 고위험 표적을 먼저 찾아 알려주는" 프로액티브 감시 기능 자체가 사라진다. 유료 tier로 옮기거나 데모할 때 되살리려면 코드를 다시 써야 한다.
- **설정 플래그로 감싸서 기본값 OFF**: 채택. 기능은 코드에 그대로 남아있고, 환경변수 하나로 언제든 복원 가능하다.

```yaml
# application.yaml
ai:
  auto-analysis:
    enabled: ${AI_AUTO_ANALYSIS_ENABLED:false}
```

```java
// ThreatAnalysisService.java
@Async("aiAnalysisExecutor")
public void analyzeAsync(TargetEvent event) {
    if (!autoAnalysisEnabled) return;   // 제일 먼저 체크 -- 꺼져있으면 아래 로직 전부 스킵
    if (!isAiEnabled()) return;
    if (!shouldAnalyze(event.getTargetId())) return;
    if (!warrantsAiAnalysis(event)) return;
    ...
}
```

기본값이 `false`이므로, 이제 Gemini는 **사용자가 대시보드에서 "AI 위협분석" 버튼을 눌러 `/api/v1/threat-analysis/analyze`를 수동으로 호출했을 때만** 소모된다. 규칙 기반 위협 등급(색상/등급 표시)은 이 플래그와 무관하게 항상 즉시 계산되므로, 자동분석을 꺼도 대시보드의 위협 등급 표시 자체는 그대로 동작한다 — 없어지는 건 오직 "AI가 먼저 찾아서 SITREP까지 만들어주는" 부분뿐이다.

데모나 유료 tier 전환 시에는 `AI_AUTO_ANALYSIS_ENABLED=true` 환경변수만 추가하면 원래 동작으로 즉시 복원된다.

## 6. 응급 차단 — 쿼터 리셋 대기 중 완전히 끄는 법

쿼터가 이미 초과된 상태에서 리셋(태평양시간 자정)을 기다리는 동안, Gemini를 아예 안 부르게 두 서비스를 내렸다:

- **threat-intel-ai-service** (Python, 전용 AI 챗봇 마이크로서비스): GitOps로 `replicas: 0` 커밋 → ArgoCD가 자동 반영. 지도/추적 기능과 완전히 분리된 서비스라 이걸 내려도 대시보드 본체는 영향 없음.
- **target-tracking-service** (Java, AI 위협분석 기능만): `target-tracking-secrets` Secret의 `gemini-api-key` 값을 `PLACEHOLDER`로 패치 후 pod 재시작 → `isAiEnabled()`가 false를 반환해 Gemini를 아예 안 부름. 이 Secret은 ArgoCD가 관리하는 리소스가 아니라(`kubectl apply`로 별도 적용됨) `kubectl patch`로 직접 복구 가능.

```bash
# 복구 (쿼터 리셋 후)
kubectl patch secret target-tracking-secrets -n c4i --type merge -p \
  '{"data":{"gemini-api-key":"<원래 base64 값>"}}'
kubectl rollout restart deployment target-tracking-service -n c4i
```

## 7. 결론

무료 tier API를 실서비스 트래픽(ADS-B 같은 지속적 이벤트 스트림)에 물릴 때는 "버튼 누른 만큼만 쓴다"는 게 당연해 보이지만, Kafka Consumer 안에 파묻힌 자동 트리거 하나 때문에 실제로는 그렇지 않았다. 이런 종류의 버그는 사용자 행동과 API 소비량 사이의 인과관계가 코드를 직접 안 보면 파악이 안 된다는 게 특징이다 — "몇 번 안 눌렀는데 왜?"라는 질문 자체가 정확한 디버깅 단서였고, 실제로 `TargetConsumer.java` 한 줄(`analyzeAsync(event)`가 모든 Kafka 메시지마다 무조건 호출됨)이 답이었다.

## 관련 문서

- `docs/concepts/03-dashboard-ai-integration.md` — AI 위협분석 최초 구현
- `docs/concepts/06-heading-multi-region-and-adsb-coverage-limits.md` — 이 사고의 배경이 된 다중 지역 ADS-B 폴링(군용기 상시 감지)
