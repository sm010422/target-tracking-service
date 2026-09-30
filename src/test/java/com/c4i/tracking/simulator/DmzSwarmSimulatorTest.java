package com.c4i.tracking.simulator;

import com.c4i.tracking.kafka.TargetProducer;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DroneSimulator(기존 시뮬레이션 버튼)는 매 라운드 좌표를 완전 랜덤으로 다시 뽑아서
 * 순간이동하는 구조였다 -- 이 테스트는 DmzSwarmSimulator가 실제로 "전진"하는지,
 * 즉 destinationPoint(위치+방위각+거리 -> 다음 위치)가 물리적으로 말이 되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DmzSwarmSimulatorTest {

    @Mock
    private TargetProducer targetProducer;

    @Test
    void 정남향_이동은_위도만_감소하고_경도는_그대로다() {
        double[] next = DmzSwarmSimulator.destinationPoint(38.25, 127.20, 180.0, 10.0);

        assertThat(next[0]).isLessThan(38.25);
        assertThat(next[1]).isCloseTo(127.20, Offset.offset(0.001));
    }

    @Test
    void 정동향_이동은_경도만_증가하고_위도는_그대로다() {
        double[] next = DmzSwarmSimulator.destinationPoint(38.25, 127.20, 90.0, 10.0);

        assertThat(next[0]).isCloseTo(38.25, Offset.offset(0.001));
        assertThat(next[1]).isGreaterThan(127.20);
    }

    @Test
    void 이동거리가_클수록_더_멀리_간다() {
        double[] near = DmzSwarmSimulator.destinationPoint(38.25, 127.20, 180.0, 5.0);
        double[] far = DmzSwarmSimulator.destinationPoint(38.25, 127.20, 180.0, 20.0);

        assertThat(far[0]).isLessThan(near[0]);
    }

    @Test
    void 거리가_0이면_제자리다() {
        double[] next = DmzSwarmSimulator.destinationPoint(38.25, 127.20, 180.0, 0.0);

        assertThat(next[0]).isCloseTo(38.25, Offset.offset(1e-9));
        assertThat(next[1]).isCloseTo(127.20, Offset.offset(1e-9));
    }

    @Test
    void 시작하면_true를_반환하고_이미_실행중이면_false를_반환한다() {
        DmzSwarmSimulator simulator = new DmzSwarmSimulator(targetProducer);

        boolean first = simulator.start(3, 4);
        boolean second = simulator.start(3, 4);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(simulator.isRunning()).isTrue();

        simulator.stop();
    }
}
