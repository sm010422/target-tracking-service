package com.c4i.tracking.domain.asset;

import java.util.Set;

/**
 * 요격 자산 1개의 고정 프로필. 실제 부대 편성이 아니라 수도권 인근 실존 기지
 * 좌표를 참고한 시뮬레이션 데이터 -- ammoCount는 요청마다 소모/보충되지 않는
 * 고정값이다 (재고 추적은 이번 스코프 밖, docs 참고).
 */
public record InterceptAsset(
    String name,
    Set<String> compatibleTargetTypes,
    double baseLatitude,
    double baseLongitude,
    double speedKmh,
    double fuelEnduranceMinutes,
    int ammoCount
) {
}
