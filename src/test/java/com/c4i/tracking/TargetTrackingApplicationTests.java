package com.c4i.tracking;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 전체 스프링 컨텍스트를 실제로 띄워 Postgres/Kafka/Redis 연결까지 검증하는
 * 통합 테스트. CI 러너에는 그 인프라가 없어서 build.gradle의
 * {@code excludeTags 'integration'}로 기본 test 태스크에서 제외된다 --
 * 로컬에서 {@code docker compose up -d postgres redis kafka zookeeper} 띄운
 * 뒤 IDE에서 직접 실행하거나 build.gradle의 excludeTags를 임시로 지우고 돌린다.
 */
@Tag("integration")
@SpringBootTest
class TargetTrackingApplicationTests {

	@Test
	void contextLoads() {
	}

}
