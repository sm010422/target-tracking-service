package com.c4i.tracking.domain.asset;

/**
 * 요격 자산 1개에 대한 계산된 추천 결과. distanceKm/etaMinutes는 하버사인
 * 거리와 자산 속도로 계산한 실제 수치이고(이전의 random READY/COOLDOWN
 * 시뮬레이션을 대체), feasible은 "탄약이 있고 ETA가 연료/반응시간 예산 안에
 * 드는가"로 판정한다.
 */
public record AssetRecommendation(
    String assetName,
    double distanceKm,
    double etaMinutes,
    int ammoCount,
    boolean feasible
) {
}
