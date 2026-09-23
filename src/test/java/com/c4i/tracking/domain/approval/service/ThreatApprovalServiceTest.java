package com.c4i.tracking.domain.approval.service;

import com.c4i.tracking.common.exception.ApprovalNotFoundException;
import com.c4i.tracking.domain.approval.dto.ThreatApprovalDto;
import com.c4i.tracking.domain.approval.entity.ThreatApproval;
import com.c4i.tracking.domain.approval.repository.ThreatApprovalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ThreatApprovalServiceTest {

    @Mock
    private ThreatApprovalRepository repository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ThreatApprovalService service;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new ThreatApprovalService(repository, messagingTemplate, meterRegistry);
    }

    @Test
    @DisplayName("LOW/MEDIUM 등급은 승인 요청을 생성하지 않는다")
    void doesNotCreateForLowSeverity() {
        service.createIfNeeded("T-1", "DRONE", "LOW", "sitrep");
        service.createIfNeeded("T-1", "DRONE", "MEDIUM", "sitrep");

        verify(repository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("HIGH/CRITICAL 등급은 승인 요청을 생성하고 브로드캐스트한다")
    void createsForHighSeverity() {
        given(repository.findFirstByTargetIdAndStatusOrderByRequestedAtDesc("T-2", "PENDING"))
            .willReturn(Optional.empty());

        service.createIfNeeded("T-2", "DRONE", "HIGH", "sitrep");

        ArgumentCaptor<ThreatApproval> captor = ArgumentCaptor.forClass(ThreatApproval.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTargetId()).isEqualTo("T-2");
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        verify(messagingTemplate).convertAndSend(eq("/topic/approvals"), any(ThreatApprovalDto.Response.class));
    }

    @Test
    @DisplayName("같은 표적에 이미 PENDING 요청이 있으면 중복 생성하지 않는다")
    void skipsDuplicatePendingRequest() {
        given(repository.findFirstByTargetIdAndStatusOrderByRequestedAtDesc("T-3", "PENDING"))
            .willReturn(Optional.of(ThreatApproval.builder()
                .targetId("T-3").targetType("DRONE").threatLevel("HIGH").sitrep("이전 요청").build()));

        service.createIfNeeded("T-3", "DRONE", "CRITICAL", "새 sitrep");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 승인 요청을 결정하려 하면 ApprovalNotFoundException")
    void decideThrowsWhenNotFound() {
        given(repository.findById(999L)).willReturn(Optional.empty());

        ThreatApprovalDto.DecisionRequest request =
            new ThreatApprovalDto.DecisionRequest("APPROVED", "operator1", "사유");

        assertThatThrownBy(() -> service.decide(999L, request))
            .isInstanceOf(ApprovalNotFoundException.class);
    }

    @Test
    @DisplayName("decision 값이 APPROVED/REJECTED가 아니면 IllegalArgumentException")
    void decideThrowsOnInvalidDecision() {
        ThreatApproval approval = ThreatApproval.builder()
            .targetId("T-4").targetType("DRONE").threatLevel("HIGH").sitrep("sitrep").build();
        given(repository.findById(1L)).willReturn(Optional.of(approval));

        ThreatApprovalDto.DecisionRequest request =
            new ThreatApprovalDto.DecisionRequest("MAYBE", "operator1", "사유");

        assertThatThrownBy(() -> service.decide(1L, request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정상적인 승인 결정은 상태를 갱신하고 브로드캐스트한다")
    void decideUpdatesStatusAndBroadcasts() {
        ThreatApproval approval = ThreatApproval.builder()
            .targetId("T-5").targetType("MISSILE").threatLevel("CRITICAL").sitrep("sitrep").build();
        given(repository.findById(1L)).willReturn(Optional.of(approval));

        ThreatApprovalDto.DecisionRequest request =
            new ThreatApprovalDto.DecisionRequest("APPROVED", "operator1", "요격 승인");

        ThreatApprovalDto.Response response = service.decide(1L, request);

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getDecidedBy()).isEqualTo("operator1");
        assertThat(approval.getDecidedAt()).isNotNull();
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/approvals"), any(ThreatApprovalDto.Response.class));
    }
}
