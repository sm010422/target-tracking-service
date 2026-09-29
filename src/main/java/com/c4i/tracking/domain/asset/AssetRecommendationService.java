package com.c4i.tracking.domain.asset;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * MSS(Maven Smart System)의 "Decide" 단계 -- 표적을 지정하면 가용 자산 중
 * 도달시간(ETA) 기준으로 순위를 매겨 상위 3개를 추천 -- 를 참고해서, 기존의
 * "(시뮬레이션 데이터) READY/COOLDOWN 랜덤" 응답을 실제 하버사인 거리 계산
 * 기반 추천으로 교체했다.
 */
@Service
public class AssetRecommendationService {

    private static final int TOP_N = 3;
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * targetType과 호환되는 자산만 후보로 삼아 ETA 오름차순으로 상위 3개를 반환한다.
     * feasible=false인 자산도 포함될 수 있다 -- "쓸 만한 옵션이 없다"는 것도
     * 의사결정자에게 그대로 보여주는 게 랜덤 READY 표시보다 정직하다.
     */
    public List<AssetRecommendation> recommend(String targetType, double targetLat, double targetLon) {
        return InterceptAssetCatalog.ASSETS.stream()
            .filter(asset -> asset.compatibleTargetTypes().contains(targetType))
            .map(asset -> toRecommendation(asset, targetLat, targetLon))
            .sorted(Comparator.comparingDouble(AssetRecommendation::etaMinutes))
            .limit(TOP_N)
            .toList();
    }

    private AssetRecommendation toRecommendation(InterceptAsset asset, double targetLat, double targetLon) {
        double distanceKm = haversineKm(asset.baseLatitude(), asset.baseLongitude(), targetLat, targetLon);
        double etaMinutes = (distanceKm / asset.speedKmh()) * 60.0;
        boolean feasible = asset.ammoCount() > 0 && etaMinutes <= asset.fuelEnduranceMinutes();
        return new AssetRecommendation(asset.name(), distanceKm, etaMinutes, asset.ammoCount(), feasible);
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
