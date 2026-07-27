# 개념 정리 — 대시보드를 Next.js로 분리해서 Vercel에 올리기

`docs/dashboard-data-and-ai-audit.md`에서 지적한 "프론트가 전역 변수 + `innerHTML` 직접 조작으로 커지고 있다"는 문제를 실제로 풀어본 기록. 별도 k3s 배포 단위를 추가하는 대신, **Vercel에 독립 배포되는 별도 리포([c4i-dashboard-frontend](https://github.com/sm010422/c4i-dashboard-frontend))**로 뺐다.

## 1. 왜 k3s에 4번째 서비스로 안 얹었나

이 프로젝트엔 이미 `target-tracking-service`, `threat-intel-ai-service`, 그리고 그 둘이 쓰는 Kafka/Postgres/Redis/Qdrant까지 떠 있다. 프론트를 또 하나의 Docker 이미지 + Deployment + Ingress로 얹으면:
- 홈랩 클러스터(메모리 여유가 넉넉하지 않다는 건 이미 여러 번 실측함)에 부담이 추가되고
- CI/CD 파이프라인(GitHub Actions → Docker Hub → ArgoCD Image Updater)을 또 하나 통째로 복제해야 한다

Vercel은 정적/서버리스 프론트엔드를 무료로 호스팅해주므로, **클러스터를 전혀 안 건드리고** 프론트만 독립적으로 배포할 수 있다. 결과적으로 이 시스템은 "백엔드는 k3s + ArgoCD GitOps, 프론트는 Vercel"이라는 서로 다른 두 배포 방식이 하나의 서비스를 이루는 구조가 됐다.

## 2. 브라우저가 클러스터를 직접 호출하게 하면서 필요해진 것 — CORS

기존 `static/index.html`은 target-tracking-service가 직접 서빙해서 같은 오리진이었다. Vercel로 옮기면 브라우저 입장에서 `https://c4i-dashboard-frontend.vercel.app`(가칭)이 `https://k3s-master.taildcdcee.ts.net`(전혀 다른 오리진)을 호출하는 모양이 된다.

- **WebSocket(`/ws`)**: 이미 `WebSocketConfig`에 `setAllowedOriginPatterns("*")`가 있어서 손댈 게 없었다 — SockJS/STOMP 핸드셰이크는 WebSocket 프로토콜이라 브라우저의 동일 출처 정책(CORS)이 REST와 다르게 적용된다.
- **REST(`/api/**`)**: CORS 설정이 아예 없었다 (same-origin이라 필요 없었으니까). `WebConfig.java`를 새로 추가해서 `/api/**`에 `allowedOriginPatterns("*")`를 걸었다.
- **threat-intel-ai-service의 `/ai/chat`**: 이미 FastAPI 쪽에 전체 오리진 허용 CORS 미들웨어가 있어서(대시보드-in-static-html 시절에 로컬 개발 편의를 위해 넣어둔 것) 손댈 필요가 없었다 — 우연히 이번 분리에도 그대로 들어맞았다.

## 3. 분리하면서 실제로 발견한 별개의 프로덕션 버그

새 프론트에서 "AI 위협분석" 버튼을 실제로 눌러보는(정확히는 그 요청을 흉내낸 curl 테스트) 과정에서 500 에러를 만났다. CORS 헤더는 정상적으로 붙어 있었으니(`access-control-allow-origin`이 500 응답에도 포함) 프론트/CORS 문제는 아니었고, pod 로그를 보니:

```
Caused by: java.util.concurrent.RejectedExecutionException: Task ... rejected from
ThreadPoolTaskExecutor$1[Running, pool size = 4, active threads = 4, queued tasks = 100, completed tasks = 33]
```

`aiAnalysisExecutor`(원래 가짜 드론 3대만 상대하도록 설계된 스레드풀, 큐 용량 100)가 꽉 차서 새 작업을 거부하고 있었다. 원인은 `AdsbFiPollingService`(`docs/concepts/04-adsb-fi-live-feed-integration.md`)가 20초마다 서로 다른 실제 항공기 50~60대를 흘려보내는데, 그 항공기 대부분이 반경을 스쳐 지나가는 1~2분 안에 다시는 안 보여서 **targetId별 10분 쿨다운이 사실상 무력화**되고, 거의 매 사이클마다 새 AI 분석 작업이 큐에 쌓였던 것.

민항기는 순항 속도(800~900km/h)가 규칙 엔진의 `speed > 200` 기준을 거의 항상 넘겨서 대부분 `MEDIUM` 등급이 나온다 — "LOW만 건너뛰자"로는 부하가 거의 안 줄어드는 셈이라, 기준을 다르게 잡았다:

```java
private boolean warrantsAiAnalysis(TargetEvent event) {
    if (!"AIRCRAFT".equals(event.getTargetType())) return true;  // DRONE/MISSILE은 항상
    if ("MILITARY".equals(event.getStatus())) return true;        // 군용기는 항상
    String level = calculateRuleBasedThreatLevel(event);
    return "HIGH".equals(level) || "CRITICAL".equals(level);
}
```

평범하게 순항 중인 민항기(등급 MEDIUM 이하, 군용 아님)는 규칙 기반 등급만 적용하고 LLM 호출 자체를 건너뛴다. 실제로 실무에서도 모든 여객기에 AI 분석을 돌릴 이유가 없다는 점에서, 이 필터는 성능 문제를 고친 것이면서 동시에 더 현실적인 설계이기도 하다.

**이 버그를 발견한 경로가 흥미롭다** — 프론트엔드를 분리하는 작업 자체와는 무관한 백엔드 용량 문제였는데, 새 프론트에서 실제로 그 버튼을 눌러보는 통합 테스트를 하지 않았다면 한동안 몰랐을 것이다. curl로 엔드포인트 하나만 스팟체크하는 것과, 실제 클라이언트가 쓰는 경로를 그대로 재현해보는 것의 차이.

## 4. 남은 절충 — BFF 없이 브라우저가 클러스터를 직접 호출

이 프론트는 Next.js를 쓰지만 서버 사이드 API 라우트(BFF)를 전혀 안 둔다 — 모든 요청이 브라우저에서 직접 클러스터로 나간다. 이유는 단순히 지금 규모에서 굳이 필요 없어서다 (인증도 없고, 백엔드가 이미 CORS를 열어줬으니). 나중에 인증이 붙거나 클러스터 주소를 아예 숨기고 싶어지면, 그때는 Next.js API 라우트나 Vercel Edge Function으로 프록시를 얹는 걸 재검토할 지점.

## 관련 문서

- `docs/dashboard-data-and-ai-audit.md` — 이 분리 작업의 동기가 된 감사 문서
- `docs/concepts/04-adsb-fi-live-feed-integration.md` — 이번에 발견한 큐 과부하의 원인이 된 ADS-B 피드
- [c4i-dashboard-frontend](https://github.com/sm010422/c4i-dashboard-frontend) — 분리된 프론트 리포 자체
