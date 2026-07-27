# 개념 정리 — 대시보드에 AI 기능을 실제로 연결하면서 드러난 것들

`docs/ai-analysis.md`, `threat-intel-ai-service`의 `/chat` 모두 오래전에 구현은 끝나 있었지만, `static/index.html` 대시보드에는 연결된 적이 없었다 — WebSocket으로 표적 위치 보여주는 것과 시뮬레이터 버튼, 딱 두 가지만 붙어 있었다. curl/port-forward로만 검증했지 브라우저에서 눌러본 적은 없었다는 뜻이다. 이 문서는 그 연결을 붙이면서 실제로 드러난 것들을 정리한다.

## 1. 두 가지를 붙였다

**① AI 위협분석 모달** — 표적 카드마다 "🔍 AI 위협분석" 버튼을 추가해서, 이 서비스 자체의 `/api/v1/threat-analysis/analyze`(Spring AI + pgvector RAG)를 호출하고 결과(`threatLevel`, `sitrep`, `similarPatterns`)를 모달로 띄운다. 이 서비스 자기 자신의 API를 부르는 거라 별다른 라우팅 이슈가 없다 — 그냥 fetch 한 번.

**② AI 챗봇 패널** — `threat-intel-ai-service`(별도 Python 리포)의 `/chat`을 SSE로 스트리밍해서 채팅 UI로 보여준다. 이건 다른 서비스를 부르는 거라 브라우저 관점에서 문제가 하나 있었다.

## 2. 다른 서비스를 브라우저에서 직접 부르는 문제 — CORS와 mixed content

`threat-intel-ai-service`는 이 서비스와 별개의 k8s Service(ClusterIP)라서, 브라우저가 직접 `http://<다른 IP>:8000/chat` 같은 절대 URL을 호출하게 하려면 두 가지 벽에 부딪힌다.

1. **CORS**: 다른 오리진이니 서버가 명시적으로 허용해야 한다.
2. **Mixed content**: 이 대시보드는 Tailscale Funnel을 통해 **HTTPS**로 서빙되는데(`k3s-msa-infrastructure/docs/Public-Access-via-Tailscale-Funnel.md`), 만약 AI 서비스를 평범한 HTTP NodePort로 노출했다면 브라우저가 HTTPS 페이지에서 HTTP로의 요청을 통째로 막아버린다.

두 문제를 한 번에 피하는 방법은 **같은 오리진으로 만드는 것**이었다. `threat-intel-ai-service`가 자기 라우터를 `/ai` prefix로 한 번 더 등록하고(`app/main.py`), 이 서비스가 이미 쓰고 있는 Traefik Ingress에 `/ai` 경로 규칙을 하나 추가해서(`k3s-msa-infrastructure/apps/threat-intel-ai-service/ingress.yaml`), 브라우저 입장에서는 그냥 같은 사이트의 다른 경로(`/ai/chat`)를 부르는 것처럼 보이게 만들었다. CORS도, mixed content도 애초에 발생하지 않는다 — Traefik이 내부적으로 다른 Service로 라우팅해주는 것뿐이라 브라우저는 그 사실을 모른다.

## 3. `EventSource`를 못 쓰는 이유 — SSE를 수동으로 파싱

브라우저의 표준 SSE 클라이언트인 `EventSource`는 **GET 요청만** 지원한다. `/chat`은 질문을 body로 보내는 POST라서 `EventSource`를 그대로 쓸 수 없다. 대신 `fetch` + `ReadableStream`으로 직접 파싱했다:

```js
const reader = res.body.getReader();
const decoder = new TextDecoder();
let buffer = '';

while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const blocks = buffer.split('\n\n');
    buffer = blocks.pop(); // 아직 안 끝난 마지막 조각은 다음 루프로
    for (const block of blocks) handleSseBlock(block, assistantEl);
}
```

SSE 프레임은 빈 줄(`\n\n`)로 구분되니, 청크가 올 때마다 버퍼에 쌓고 완성된 블록만 잘라서 처리한다. 마지막 조각(`blocks.pop()`)은 아직 완성 안 된 프레임일 수 있어서 버퍼에 남겨두고 다음 청크와 합친다 — 네트워크 청크 경계가 SSE 프레임 경계와 일치한다는 보장이 없어서 이 버퍼링이 필요하다.

`threat-intel-ai-service`가 보내는 이벤트 종류(`route`, `tool_call`, `sources`, `error`, `done`, 그리고 이름 없는 토큰 데이터)를 각각 다르게 렌더링한다 — 특히 `tool_call` 이벤트는 "🔧 도구 호출: assess_threat_level → MEDIUM" 같은 식으로 그대로 노출해서, AI가 실제로 어떤 판단을 했는지 투명하게 보여준다.

## 4. 버튼을 연결하고서야 발견한 진짜 장애

이 작업에서 가장 중요한 발견은 UI 코드가 아니라, **UI를 연결하는 과정에서 드러난 버그**였다. 위협분석 버튼을 실제로 눌러보니 500 에러가 났다:

```
java.lang.RuntimeException: Failed to generate content
Caused by: com.google.genai.errors.ClientException: 404 . This model models/gemini-2.5-flash
is no longer available to new users.
```

`application.yaml`이 여전히 `gemini-2.5-flash`를 가리키고 있었다 — `threat-intel-ai-service`(Python)에서 똑같은 404를 겪고 `gemini-flash-lite-latest`로 바꾼 게 며칠 전인데, **같은 계정의 Java 서비스에도 같은 문제가 있다는 걸 그때 확인 안 했다.** curl로 `/api/v1/threat-analysis/status`만 확인하면 `aiEnabled: true`가 나오니(이건 API 키 존재 여부만 확인하는 엔드포인트라 실제 생성 호출을 안 함) 정상처럼 보였고, `/analyze`를 실제로 호출해본 적이 최근 며칠간 없었다.

**이게 이번 작업의 핵심 교훈이다**: 기능이 코드로 구현돼 있고 상태 엔드포인트가 "정상"이라고 답해도, **그 기능을 실제로 실행하는 경로가 뭔가에 연결되어 있지 않으면 회귀(regression)가 조용히 쌓인다.** 대시보드에 버튼 하나 붙이는 게 "UI 작업"처럼 보였지만, 실제로는 몇 달째 아무도 실행 안 해본 코드 경로를 처음으로 실행시킨 것이었고 그 경로가 깨져 있었다. `application.yaml`의 모델을 `gemini-flash-lite-latest`로 맞추고(Python 쪽과 동일한 모델), 재배포 후 실제로 CRITICAL 등급과 SITREP이 정상 생성되는 것까지 확인했다.

## 5. 정리

| 붙인 것 | 부르는 대상 | 겪은 문제 | 해결 |
|---|---|---|---|
| AI 위협분석 모달 | 이 서비스 자신의 `/api/v1/threat-analysis/analyze` | `gemini-2.5-flash` 404 (연결 안 해봐서 몰랐던 회귀) | `gemini-flash-lite-latest`로 교체 |
| AI 챗봇 패널 | `threat-intel-ai-service`의 `/chat` (다른 서비스) | CORS + mixed content | Traefik Ingress `/ai` path로 same-origin화 |
| 챗봇 스트리밍 | SSE (POST 필요) | `EventSource`가 POST 미지원 | `fetch` + `ReadableStream` 수동 파싱 |
