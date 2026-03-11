# 📡 C4I 시스템 - Target Tracking Service

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg?logo=springboot)](#) [![WebSocket](https://img.shields.io/badge/WebSocket-RealTime-yellow.svg)](#) [![Java](https://img.shields.io/badge/Java-17-orange.svg?logo=java)](#)

## 📌 프로젝트 개요
실시간 전술 객체 추적 시스템(MSA)의 핵심 비즈니스 로직을 담당하는 마이크로서비스입니다. 

레이더, 드론 등 다양한 센서로부터 유입되는 **대규모 위치 데이터(위도, 경도, 고도, 속도)를 실시간으로 수집 및 가공**하고, 이를 지휘통제실의 프론트엔드 대시보드로 즉각 전송하여 시각화할 수 있도록 지원하는 고성능 데이터 처리 백엔드입니다.

## ✨ 핵심 기능 (Key Features)

### 1. 🚀 실시간 레이더 데이터 수집 파이프라인
* 초당 다량으로 발생하는 전술 객체(드론, 항공기)의 가상 이동 좌표 데이터를 지연 없이 수신할 수 있도록 비동기 처리 기반의 API를 설계합니다.

### 2. 📡 실시간 양방향 통신 (WebSocket)
* REST API의 한계(단방향)를 극복하고, 클라이언트(지휘 대시보드)에 객체의 이동 경로를 실시간으로 끊김없이 그려내기 위해 **WebSocket 통신 환경**을 구축했습니다.

### 3. 🛡️ 고가용성 및 무중단 데이터 처리 (Fault Tolerance)
* K3s 쿠버네티스 클러스터 위에서 다중 파드(Pod)로 복제되어 실행됩니다.
* 노드(서버) 장애 발생 시에도 다른 노드에서 즉각적으로 트래픽을 넘겨받아, 전술 데이터의 유실 없이 시스템이 지속적으로 운영되는 무중단(Zero-Downtime) 환경을 염두에 두고 설계되었습니다.

## 🛠 기술 스택 (Tech Stack)
* **Framework:** Java 21, Spring Boot 3.5.11, Spring WebSockets
* **Database:** PostgreSQL / Redis (도입 예정)
* **Infra:** K3s (Kubernetes), Docker

## 🔗 연관 리포지토리
* 🏗️ **[K3s MSA Infrastructure (인프라 구성도)](https://github.com/sm010422/k3s-msa-infrastructure)**
* 🚪 **[Defense API Gateway (트래픽 라우팅)](https://github.com/sm010422/defense-api-gateway)** target-tracking-service
