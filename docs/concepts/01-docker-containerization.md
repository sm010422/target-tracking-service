# 개념 정리 — Docker 컨테이너화

이번에 `target-tracking-service`에 Dockerfile을 새로 만들면서 나온 개념들. "왜 이렇게 써야 하는가"에 초점.

## 1. 이미지(Image)와 컨테이너(Container)는 다른 것이다

- **이미지**: 실행 파일 + 라이브러리 + 설정을 하나로 굳힌 "읽기 전용 템플릿". `docker build`로 만든다.
- **컨테이너**: 그 이미지를 실제로 "실행 중인 상태"로 띄운 것. `docker run`으로 만든다.

비유하면 이미지는 클래스(class), 컨테이너는 인스턴스(instance)에 가깝다. 같은 이미지로 컨테이너를 몇 개든 띄울 수 있고(우리 `docker-compose.yml`의 `defense_app` 하나가 인스턴스 하나), 컨테이너를 지워도 이미지는 남아있다.

## 2. 왜 멀티스테이지 빌드인가

우리 `Dockerfile`은 2단계로 나뉜다:

```dockerfile
# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS build
...
RUN ./gradlew bootJar --no-daemon -x test

# ---- Run stage ----
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /app/build/libs/*.jar app.jar
```

**JDK와 JRE는 용도가 다르다.** JDK(Java Development Kit)는 `javac` 같은 컴파일러까지 포함해서 무겁고, JRE(Java Runtime Environment)는 이미 컴파일된 `.class`/`.jar`를 실행만 하는 최소 구성이다.

빌드하려면 JDK가 필요하지만(Gradle이 소스를 컴파일해야 하니까), **실행할 때는 컴파일러가 전혀 필요 없다.** 만약 단일 스테이지로 JDK 이미지 위에서 빌드부터 실행까지 다 하면, 최종 이미지 안에 컴파일러·Gradle 캐시·소스코드까지 전부 남아서 이미지가 훨씬 커진다.

멀티스테이지는 `build`라는 임시 스테이지에서 무거운 빌드 작업을 다 끝낸 다음, `COPY --from=build`로 **결과물(jar 파일) 딱 하나만** 가벼운 실행용 이미지로 복사해온다. `build` 스테이지 자체는 최종 이미지에 전혀 포함되지 않는다 — 빌드 도구가 프로덕션 이미지에 남지 않으니 이미지 크기도 줄고 공격 표면(attack surface)도 줄어든다.

## 3. 레이어 캐싱과 `COPY` 순서

```dockerfile
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test
```

Docker 이미지는 각 명령(`RUN`, `COPY` 등)마다 레이어(layer)를 하나씩 쌓는다. 레이어는 캐시되기 때문에, **이전 레이어와 입력이 똑같으면 다시 실행하지 않고 캐시를 재사용**한다.

그래서 의존성 설치(`build.gradle` 기반)와 소스 코드 복사(`src`)를 의도적으로 분리했다: 소스 코드(`src/`)는 자주 바뀌지만 `build.gradle`(의존성 목록)은 거의 안 바뀐다. 이 순서면 소스만 고쳤을 때 의존성 다운로드 레이어는 캐시를 그대로 쓰고, `COPY src`부터만 다시 실행된다 — 재빌드가 훨씬 빨라진다. 만약 `COPY . .`로 한 번에 다 복사했다면 소스 한 줄만 바꿔도 의존성부터 전부 다시 받아야 한다.

## 4. `.dockerignore`가 하는 일

`docker build .`를 실행하면 Docker는 현재 디렉토리(`.`) 전체를 "빌드 컨텍스트"로 데몬에 전송한다. `.dockerignore`는 `.gitignore`와 똑같은 문법으로, 이 전송 대상에서 뺄 것을 지정한다.

```
.git
.gradle
build
bin
...
```

`.git`, 이미 만들어진 `build/` 산출물, IDE 설정 같은 걸 빼야 빌드 컨텍스트 전송이 빠르고, 실수로 `.env` 같은 민감 파일이 이미지 레이어에 들어가는 것도 막는다.

## 5. `docker-compose`는 뭘 해주는가 — 컨테이너 간 네트워킹

`docker-compose.yml`로 여러 컨테이너(`postgres`, `redis`, `kafka`, `app` 등)를 같이 띄우면, Compose가 자동으로 **전용 네트워크**를 만들고 그 안에서 서비스 이름 = 호스트 이름으로 서로를 찾을 수 있게 해준다.

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/defense_db
```

`postgres`라는 이름 자체가 DNS처럼 동작한다 — Compose가 내부 DNS를 통해 `postgres`를 해당 컨테이너의 IP로 풀어준다. 컨테이너 밖(맥북 터미널)에서는 이 이름이 안 먹힌다는 걸 주의해야 한다. `ports: ["5432:5432"]`처럼 포트를 명시적으로 열어야 호스트에서 `localhost:5432`로 접근 가능해진다. **컨테이너 안에서 다른 컨테이너로 갈 때는 서비스 이름, 호스트에서 컨테이너로 갈 때는 `localhost` + 매핑된 포트** — 이 둘을 헷갈리면 안 된다.

## 6. Kafka `advertised.listeners`가 까다로운 이유

이번 작업에서 가장 헷갈렸던 부분. Kafka 브로커에 접속하는 과정은 2단계다:

1. 클라이언트가 **부트스트랩 서버**(`bootstrap-servers`)로 최초 접속해서 "지금 클러스터에 어떤 브로커들이 있고, 각각 어디로 접속해야 하는지" 메타데이터를 받는다.
2. 그 메타데이터에 적힌 주소(**advertised listener**)로 실제 프로듀스/컨슈머 요청을 보낸다.

문제는 **"어디서 접속하느냐"에 따라 같은 브로커라도 올바른 주소가 다르다**는 것. 컨테이너 안에서 접속하는 앱은 `kafka`(Compose 네트워크 안 서비스 이름)로 가야 하고, 맥북 터미널에서 직접 붙는 CLI 도구는 `localhost`로 가야 한다. 브로커가 메타데이터에 `localhost`만 광고(advertise)하면, 컨테이너 안의 앱은 부트스트랩까지는 성공해도 그 다음 "이제 진짜로는 localhost로 가"라는 응답을 받고 자기 자신(컨테이너 안의 localhost, 즉 브로커가 없는 곳)으로 연결을 시도해서 실패한다.

그래서 리스너를 이중으로 나눴다:

```yaml
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092,PLAINTEXT_HOST://0.0.0.0:9092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
```

브로커가 내부적으로 두 포트(29092, 9092)를 동시에 열어두고, "29092로 들어온 요청에는 `kafka:29092`라고 광고, 9092로 들어온 요청에는 `localhost:9092`라고 광고"하도록 나눈 것. 컨테이너 앱은 `kafka:29092`로, 호스트 CLI는 `localhost:9092`로 각자 맞는 경로를 쓰게 된다.

K3s 배포 때는 이 문제가 없어서(모든 클라이언트가 클러스터 안에서만 접속하므로) 리스너를 하나(`PLAINTEXT://kafka-service.c4i.svc.cluster.local:9092`)로 단순화했다 — "접속 주체가 몇 군데인지"에 따라 리스너 구성이 달라진다는 걸 보여주는 좋은 대비 사례.
