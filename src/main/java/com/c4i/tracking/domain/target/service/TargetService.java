package com.c4i.tracking.domain.target.service;

import com.c4i.tracking.domain.target.dto.TargetDto;
import com.c4i.tracking.domain.target.entity.Target;
import com.c4i.tracking.domain.target.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TargetService {

    private final TargetRepository targetRepository;

    @Transactional
    public TargetDto.Response saveTarget(TargetDto.Request request) {
        Target target = Target.builder()
                .targetId(request.getTargetId())
                .targetType(request.getTargetType())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .altitude(request.getAltitude())
                .speed(request.getSpeed())
                .status(request.getStatus())
                .build();

        Target saved = targetRepository.save(target);
        log.info("Target saved: {}", saved.getTargetId());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<TargetDto.Response> getAllTargets() {
        return targetRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TargetDto.Response> getTargetsByStatus(String status) {
        return targetRepository.findByStatus(status)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private TargetDto.Response toResponse(Target target) {
        return TargetDto.Response.builder()
                .id(target.getId())
                .targetId(target.getTargetId())
                .targetType(target.getTargetType())
                .latitude(target.getLatitude())
                .longitude(target.getLongitude())
                .altitude(target.getAltitude())
                .speed(target.getSpeed())
                .status(target.getStatus())
                .detectedAt(target.getDetectedAt() != null ?
                        target.getDetectedAt().toString() : null)
                .build();
    }
}
