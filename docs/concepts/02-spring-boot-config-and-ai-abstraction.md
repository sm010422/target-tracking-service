# 개념 정리 — Spring Boot 설정 우선순위 & Spring AI 프로바이더 추상화

OpenAI → Gemini 전환이 **코드 한 줄 안 건드리고 설정값만 바꿔서 끝난** 이유를 이해하려면 두 가지 개념이 필요하다.

## 1. Spring Boot 프로퍼티 소스 우선순위

`application.yaml`에 값을 이렇게 적어도:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/defense_db
    username: defense
    password: defense_password
```

k3s에 배포할 때는 이 값을 코드/yaml 수정 없이 그대로 덮어썼다:

```yaml
env:
  - name: SPRING_DATASOURCE_URL
    valueFrom:
      secretKeyRef: { name: target-tracking-secrets, key: db-url }
```

**Spring Boot는 여러 곳에서 설정값을 읽고, 그 중 우선순위가 높은 쪽이 이긴다.** 대략적인 순서(높은 게 이김):

1. 커맨드라인 인자 (`--spring.datasource.url=...`)
2. **환경변수** (`SPRING_DATASOURCE_URL`)
3. `application.yaml` / `application.properties`
4. `@Value`의 기본값(`:PLACEHOLDER` 같은 콜론 뒤 부분)

환경변수 이름 `SPRING_DATASOURCE_URL`이 프로퍼티 키 `spring.datasource.url`과 다르게 생긴 건 "relaxed binding" 규칙 때문이다 — 환경변수는 대문자+언더스코어만 쓸 수 있으니, Spring이 `.`을 `_`로, camelCase를 `_`로 자동 변환해서 매칭해준다. 그래서 `application.yaml`에 값이 하드코딩돼 있어도, k8s Deployment에서 같은 이름의 환경변수를 주면 그게 이긴다 — **yaml을 고칠 필요가 없다.**

반면 우리 코드에서 `${GEMINI_API_KEY:PLACEHOLDER}` 처럼 **명시적으로 `${...}` 플레이스홀더를 박아둔 값**(`spring.data.redis.host`, `spring.kafka.bootstrap-servers`, `spring.ai.google.genai.api-key`)은 정확히 그 이름의 환경변수(`SPRING_REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `GEMINI_API_KEY`)로만 오버라이드된다 — 이건 relaxed binding이 아니라 그냥 문자열 치환이라, k8s manifest의 env 이름도 `application.yaml`에 적힌 플레이스홀더 이름과 **정확히 똑같아야** 한다. (실제로 이번에 `deployment.yaml`에 `GEMINI_API_KEY` env를 추가할 때 이 이름을 정확히 맞춰야 했다.)

## 2. Spring AI의 인터페이스 추상화 — 왜 코드를 안 건드렸나

`ThreatAnalysisService`, `ThreatKnowledgeInitializer`는 OpenAI든 Gemini든 신경 안 쓰고 이렇게만 짜여 있다:

```java
private final VectorStore vectorStore;
private final ChatModel chatModel;
...
String sitrep = chatModel.call(prompt);
List<Document> similar = vectorStore.similaritySearch(...);
```

`ChatModel`, `VectorStore`는 Spring AI가 제공하는 **인터페이스**다. 실제 구현체(`OpenAiChatModel`, `GoogleGenAiChatModel` 등)는 Spring Boot의 자동 설정(auto-configuration)이 클래스패스에 어떤 스타터가 있는지 보고 알아서 빈으로 등록해준다.

```gradle
// build.gradle
implementation 'org.springframework.ai:spring-ai-starter-model-google-genai'
```

이 한 줄이 클래스패스에 있으면 Spring Boot가 `GoogleGenAiChatModel`을 `ChatModel` 빈으로 등록하고, 우리 서비스 코드는 그게 뭔지도 모른 채 `chatModel.call(prompt)`만 호출한다. **의존성 역전(Dependency Inversion)**의 실전 사례 — 구체 구현이 아니라 추상화(인터페이스)에 의존하게 짜여 있어서, 구현체를 통째로 갈아끼워도(`spring-ai-starter-model-openai` → `spring-ai-starter-model-google-genai`) 그 인터페이스를 쓰는 코드는 무사하다.

이번에 실제로 바꾼 건 딱 3곳:
1. `build.gradle`의 의존성 아티팩트
2. `application.yaml`의 `spring.ai.*` 설정 키 (프로바이더마다 설정 네임스페이스가 다름)
3. `@Value("${spring.ai.openai.api-key:...}")` → `@Value("${spring.ai.google.genai.api-key:...}")` (플레이스홀더 문자열 자체이므로 이건 어쩔 수 없이 코드 수정)

## 3. 함정 — "API 키 하나만 넣으면 되는 줄 알았는데" (Gemini Developer API vs Vertex AI)

Google이 Gemini에 접근하는 경로를 **두 가지** 제공한다는 걸 몰랐으면 여기서 막혔을 것:

| | Gemini Developer API (AI Studio) | Vertex AI |
|---|---|---|
| 인증 | API 키 하나 | GCP 서비스 계정 / ADC |
| 요금 | **무료 티어 있음** | GCP 프로젝트 결제 필요 |
| Spring AI 설정 | `spring.ai.google.genai.api-key`만 | `project-id` + `location` 필요 |

Spring AI의 `google-genai` 스타터는 **둘 다** 지원하는데, 설정에 `project-id`나 `location`이 하나라도 있으면 자동으로 Vertex AI 모드로 전환되고, 그 순간 API 키 인증은 무시되고 400 에러가 난다. 그래서 `application.yaml`에는 절대 이 두 키를 넣지 않는 게 무료 티어를 유지하는 핵심 조건이다.

이런 "같은 스타터, 다른 모드" 함정은 실제 서비스 문서를 안 읽고 프로퍼티 이름만 추측해서 넣으면 반나절을 날릴 수 있는 부분이라, jar 안의 `META-INF/spring-configuration-metadata.json`을 직접 열어서 실제 존재하는 프로퍼티 목록을 확인하는 습관이 유용하다:

```bash
unzip -p spring-ai-autoconfigure-model-google-genai-1.1.8.jar \
  META-INF/spring-configuration-metadata.json | python3 -m json.tool
```
