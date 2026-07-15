package com.c4i.tracking.domain.target.scheduler;

import com.c4i.tracking.domain.target.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TargetCleanupScheduler {

    private static final long RETENTION_HOURS = 1;

    private final TargetRepository targetRepository;

    @Scheduled(fixedRate = 5 * 60 * 1000) // 5분마다 실행
    @Transactional
    @CacheEvict(value = {"targets", "targetsByStatus"}, allEntries = true)
    public void cleanupOldTargets() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        int deleted = targetRepository.deleteByDetectedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("오래된 타겟 이력 정리: {}건 삭제 (cutoff={})", deleted, cutoff);
        }
    }
}
