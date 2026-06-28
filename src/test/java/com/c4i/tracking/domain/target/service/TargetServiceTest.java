package com.c4i.tracking.domain.target.service;

import com.c4i.tracking.common.exception.TargetNotFoundException;
import com.c4i.tracking.domain.target.dto.TargetDto;
import com.c4i.tracking.domain.target.entity.Target;
import com.c4i.tracking.domain.target.repository.TargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TargetServiceTest {

    @Mock
    private TargetRepository targetRepository;

    @InjectMocks
    private TargetService targetService;

    private Target sampleTarget;
    private TargetDto.Request sampleRequest;

    @BeforeEach
    void setUp() {
        sampleTarget = Target.builder()
                .targetId("DRONE-001")
                .targetType("DRONE")
                .latitude(37.5)
                .longitude(127.0)
                .altitude(500.0)
                .speed(120.0)
                .status("DETECTED")
                .build();

        sampleRequest = TargetDto.Request.builder()
                .targetId("DRONE-001")
                .targetType("DRONE")
                .latitude(37.5)
                .longitude(127.0)
                .altitude(500.0)
                .speed(120.0)
                .status("DETECTED")
                .build();
    }

    @Test
    @DisplayName("표적 저장 시 올바른 응답 DTO를 반환한다")
    void saveTarget_returnsResponse() {
        given(targetRepository.save(any(Target.class))).willReturn(sampleTarget);

        TargetDto.Response response = targetService.saveTarget(sampleRequest);

        assertThat(response.getTargetId()).isEqualTo("DRONE-001");
        assertThat(response.getTargetType()).isEqualTo("DRONE");
        assertThat(response.getLatitude()).isEqualTo(37.5);
        assertThat(response.getStatus()).isEqualTo("DETECTED");
        verify(targetRepository).save(any(Target.class));
    }

    @Test
    @DisplayName("전체 표적 조회 시 모든 표적 목록을 반환한다")
    void getAllTargets_returnsList() {
        given(targetRepository.findAll()).willReturn(List.of(sampleTarget));

        List<TargetDto.Response> result = targetService.getAllTargets();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetId()).isEqualTo("DRONE-001");
    }

    @Test
    @DisplayName("상태별 조회 시 해당 상태의 표적만 반환한다")
    void getTargetsByStatus_filtersCorrectly() {
        given(targetRepository.findByStatus("DETECTED")).willReturn(List.of(sampleTarget));

        List<TargetDto.Response> result = targetService.getTargetsByStatus("DETECTED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("DETECTED");
    }

    @Test
    @DisplayName("존재하는 targetId로 최신 위치 조회 시 응답을 반환한다")
    void getLatestByTargetId_found() {
        given(targetRepository.findLatestByTargetId("DRONE-001"))
                .willReturn(Optional.of(sampleTarget));

        TargetDto.Response result = targetService.getLatestByTargetId("DRONE-001");

        assertThat(result.getTargetId()).isEqualTo("DRONE-001");
        assertThat(result.getLatitude()).isEqualTo(37.5);
    }

    @Test
    @DisplayName("존재하지 않는 targetId 조회 시 TargetNotFoundException이 발생한다")
    void getLatestByTargetId_notFound_throwsException() {
        given(targetRepository.findLatestByTargetId("DRONE-999"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> targetService.getLatestByTargetId("DRONE-999"))
                .isInstanceOf(TargetNotFoundException.class)
                .hasMessageContaining("DRONE-999");
    }
}
