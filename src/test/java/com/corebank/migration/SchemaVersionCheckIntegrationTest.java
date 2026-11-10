package com.corebank.migration;

import com.corebank.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서버는 마이그레이션하지 않고 확인만 한다. 서버가 쓰는 Flyway 설정(앱 계정, 소유자 스키마)으로 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaVersionCheckIntegrationTest {

	@Autowired
	private Flyway flyway;

	/** 서버의 Flyway 는 앱 계정으로 소유자 스키마의 이력을 읽고, 마이그레이션 프로그램이 다 적용했으니 통과한다. */
	@Test
	void serverValidatesOwnerSchemaWithAppUser() {
		assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("COREBANK");
		assertThat(flyway.getConfiguration().isCreateSchemas()).isFalse();
		assertThatCode(flyway::validate).doesNotThrowAnyException();
		assertThat(flyway.info().pending()).isEmpty();
	}

	/** 적용하지 않은 마이그레이션이 하나라도 있으면 확인이 실패한다. 서버 시작 때라면 서버가 뜨지 않는다. */
	@Test
	void pendingMigrationFailsValidation() {
		Flyway withPending = Flyway.configure()
				.configuration(flyway.getConfiguration())
				.locations("classpath:db/migration", "classpath:db/pending-check")
				.load();

		assertThat(withPending.info().pending()).extracting(m -> m.getVersion().getVersion()).containsExactly("9999");
		assertThatThrownBy(withPending::validate)
				.isInstanceOf(FlywayValidateException.class)
				.hasMessageContaining("9999");
	}

}
