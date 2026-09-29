package com.c4i.tracking.domain.asset;

import java.util.List;
import java.util.Set;

/**
 * 고정 요격 자산 카탈로그. AdsbFiPollingService의 "KOREA" 권역 기준점(서울,
 * 37.5665/126.9780)을 주 방어 구역으로 삼아, 수도권 인근 실존 기지 좌표를
 * 참고해 배치했다 -- 실제 편제가 아니라 거리·속도 기반 ETA 계산이 지리적으로
 * 말이 되게 하기 위한 시뮬레이션 데이터.
 */
public final class InterceptAssetCatalog {

    public static final List<InterceptAsset> ASSETS = List.of(
        new InterceptAsset(
            "F-15K 요격편대(오산)", Set.of("AIRCRAFT", "MISSILE"),
            37.0906, 127.0296, 2000.0, 90.0, 4),
        new InterceptAsset(
            "KF-21 초계편대(수원)", Set.of("AIRCRAFT", "DRONE"),
            37.2394, 127.0079, 1800.0, 100.0, 6),
        new InterceptAsset(
            "PAC-3 패트리엇 포대(평택)", Set.of("MISSILE"),
            36.9921, 127.0890, 5000.0, 15.0, 8),
        new InterceptAsset(
            "K30 비호 대공포(수도권)", Set.of("DRONE"),
            37.5665, 126.9780, 3000.0, 10.0, 200),
        new InterceptAsset(
            "요격 드론 대대(김포)", Set.of("DRONE"),
            37.5583, 126.7906, 150.0, 40.0, 10)
    );

    private InterceptAssetCatalog() {
    }
}
