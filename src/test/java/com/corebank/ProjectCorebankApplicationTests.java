package com.corebank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 전체 스프링 설정이 Oracle 컨테이너와 함께 정상적으로 올라오는지 확인한다.
 * Flyway 실행과 JPA 스키마 검증(ddl-auto=validate)도 이 과정에서 함께 확인된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProjectCorebankApplicationTests {

	@Test
	void contextLoads() {
	}

}
