package com.c4i.tracking.domain.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-09-29 threat-intel-ai-service의 check_intercept_asset_availability가
 * "(시뮬레이션 데이터) READY/COOLDOWN 랜덤"이었던 걸 발견하고, MSS(Maven Smart
 * System)의 "표적 지정 -> AI가 연료/도달시간 계산해 top-3 추천" 패턴을 참고해서
 * 실제 하버사인 거리 기반 ETA 계산으로 교체한 것을 검증한다.
 */
class AssetRecommendationServiceTest {

    private static final double SEOUL_LAT = 37.5665;
    private static final double SEOUL_LON = 126.9780;

    private final AssetRecommendationService service = new AssetRecommendationService();

    static Stream<Arguments> compatibilityCases() {
        return Stream.of(
            Arguments.of("MISSILE", List.of("PAC-3 패트리엇 포대(평택)", "F-15K 요격편대(오산)")),
            Arguments.of("DRONE", List.of("K30 비호 대공포(수도권)", "요격 드론 대대(김포)", "KF-21 초계편대(수원)")),
            Arguments.of("AIRCRAFT", List.of("F-15K 요격편대(오산)", "KF-21 초계편대(수원)"))
        );
    }

    @ParameterizedTest(name = "{0} 표적엔 호환 자산만 추천된다")
    @MethodSource("compatibilityCases")
    @DisplayName("표적 유형과 호환되는 자산만 후보에 포함한다")
    void onlyRecommendsCompatibleAssets(String targetType, List<String> expectedCandidateNames) {
        List<AssetRecommendation> result = service.recommend(targetType, SEOUL_LAT, SEOUL_LON);

        assertThat(result).isNotEmpty();
        assertThat(expectedCandidateNames).containsAll(result.stream().map(AssetRecommendation::assetName).toList());
    }

    @Test
    @DisplayName("최대 3개까지만 반환한다")
    void limitsToTopThree() {
        List<AssetRecommendation> result = service.recommend("DRONE", SEOUL_LAT, SEOUL_LON);

        assertThat(result).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("ETA(도달 예상 시간) 오름차순으로 정렬한다")
    void sortsByEtaAscending() {
        List<AssetRecommendation> result = service.recommend("DRONE", SEOUL_LAT, SEOUL_LON);

        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).etaMinutes()).isGreaterThanOrEqualTo(result.get(i - 1).etaMinutes());
        }
    }

    @Test
    @DisplayName("수도권 인근 표적이면 수도권 근접 대공포가 가장 빠른(ETA 최소) 옵션으로 나온다")
    void nearbyAssetHasLowestEta() {
        List<AssetRecommendation> result = service.recommend("DRONE", SEOUL_LAT, SEOUL_LON);

        assertThat(result.get(0).assetName()).isEqualTo("K30 비호 대공포(수도권)");
        assertThat(result.get(0).distanceKm()).isLessThan(1.0);
    }

    @Test
    @DisplayName("호환되는 자산이 없는 표적 유형이면 빈 목록을 반환한다")
    void returnsEmptyForUnknownTargetType() {
        List<AssetRecommendation> result = service.recommend("SHIP", SEOUL_LAT, SEOUL_LON);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("탄약이 있고 ETA가 연료/반응시간 예산 안이면 feasible=true다")
    void marksFeasibleWhenWithinBudget() {
        List<AssetRecommendation> result = service.recommend("DRONE", SEOUL_LAT, SEOUL_LON);

        AssetRecommendation nearby = result.get(0);
        assertThat(nearby.feasible()).isTrue();
    }

    @Test
    @DisplayName("아주 먼 표적이면 ETA가 연료/반응시간 예산을 넘겨 feasible=false가 나올 수 있다")
    void marksInfeasibleWhenOutOfBudget() {
        // 우크라이나 키이우 권역 -- 국내 자산의 연료/반응시간 예산을 훨씬 초과하는 거리
        List<AssetRecommendation> result = service.recommend("DRONE", 50.4501, 30.5234);

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(r -> !r.feasible());
    }
}
