package com.c4i.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling  // 나중에 시뮬레이터용
public class TargetTrackingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TargetTrackingApplication.class, args);
    }
}
