# 📡 C4I 시스템 - Target Tracking Service

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.11-brightgreen.svg?logo=springboot)](#)
[![Java](https://img.shields.io/badge/Java-21-orange.svg?logo=java)](#)
[![WebSocket](https://img.shields.io/badge/WebSocket-RealTime-yellow.svg)](#)
[![Kafka](https://img.shields.io/badge/Kafka-DataPipeline-black.svg?logo=apachekafka)](#)
[![K3s](https://img.shields.io/badge/K3s-Kubernetes-blue.svg?logo=kubernetes)](#)

## 📌 프로젝트 개요

실시간 전술 객체 추적 시스템(C4I)의 핵심 백엔드 마이크로서비스입니다.

레이더, 드론 등 다양한 센서로부터 유입되는 **대규모 위치 데이터(위도, 경도, 고도, 속도)를
실시간으로 수집 및 가공**하고, 지휘통제실 대시보드로 즉각 전송하여 전술 상황을 시각화합니다.

맥북 2대 + 가상머신 3개로 구성한 실제 분산 환경(K3s 클러스터) 위에서 동작합니다.

## ✨ 핵심 기능

### 1. 🚀 실시간 센서 데이터 수집 파이프라인
- 드론 시뮬레이터가 초당 다량의 전술 객체 좌표(위도/경도/고도/속도)를 생성
- Kafka를 통한 비동기 메시지 처리로 데이터 유실 없이 수신
- PostgreSQL에 전술 객체 이력 저장

### 2. 📡 실시간 양방향 통신 (WebSocket)
- REST API의 단방향 한계를 극복
- WebSocket으로 지휘 대시보드에 객체 이동 경로 실시간 전송

### 3. 🛡️ 고가용성 및 무중단 운영 (Fault Tolerance)
- K3s 클러스터 위에서 다중 Pod로 복제 실행
- 노드 장애 시 자동 failover로 데이터 유실 없이 지속 운영

## 🏗 시스템 아키텍처
```
드론 시뮬레이터 → Kafka → Target Tracking Service → WebSocket → 지휘 대시보드
                                    ↓
                              PostgreSQL
```

## 🛠 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.5.11, Spring WebSocket |
| Message Queue | Apache Kafka |
| Database | PostgreSQL |
| Cache | Redis (구현 예정) |
| Infra | K3s (Kubernetes), Docker |

## 📋 구현 현황

- [x] 프로젝트 초기 세팅
- [x] Target 도메인 (Entity, Repository, Service, Controller)
- [x] Docker Compose 로컬 개발 환경 구성
- [ ] Kafka Producer/Consumer 연동
- [ ] WebSocket 실시간 통신
- [ ] 드론 시뮬레이터
- [ ] K3s 클러스터 배포

## 🔗 연관 리포지토리

- 🏗️ **[K3s MSA Infrastructure](https://github.com/sm010422/k3s-msa-infrastructure)** - 클러스터 인프라 구성
- 🚪 **[Defense API Gateway](https://github.com/sm010422/defense-api-gateway)** - 보안 인증 및 트래픽 라우팅
