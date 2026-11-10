package com.corebank.migration;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * 서버는 마이그레이션하지 않고, 시작할 때 DB 가 이 코드와 같은 버전인지 확인만 한다.
 *
 * <p>Flyway 의 validate 는 적용되지 않은 마이그레이션(새 V 파일, 바뀐 R 파일)이 있거나 적용된 파일이 바뀌었으면 실패한다.
 * 그러면 서버가 시작되지 않는다. 마이그레이션(MigrationApplication)을 먼저 실행하라는 뜻이다.
 * 앱 계정은 이력 테이블(flyway_schema_history)을 읽기만 할 수 있다(R__app_user_grants.sql).
 */
@Configuration(proxyBeanMethods = false)
class SchemaVersionCheck {

	@Bean
	FlywayMigrationStrategy validateOnly() {
		return Flyway::validate;
	}

	/**
	 * 이력 테이블은 앱 계정이 아니라 소유자 스키마에 있다. Flyway 는 스키마 이름의 대소문자를 구분하고 Oracle 은 따옴표 없이
	 * 만든 사용자 이름을 대문자로 저장하므로, 대문자로 넘긴다(.env 에 corebank 라고 써도 COREBANK 를 찾는다).
	 * 스키마가 없으면 만들지 않고 실패한다(앱 계정은 만들 권한도 없다).
	 */
	@Bean
	FlywayConfigurationCustomizer ownerSchema(@Value("${DB_OWNER_USERNAME}") String owner) {
		return configuration -> configuration.defaultSchema(owner.toUpperCase(Locale.ROOT)).createSchemas(false);
	}

}
