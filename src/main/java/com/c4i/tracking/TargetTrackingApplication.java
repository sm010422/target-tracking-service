package com.c4i.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling  // TargetCleanupScheduler(5분마다 오래된 데이터 정리)용
public class TargetTrackingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TargetTrackingApplication.class, args);
    }
}
