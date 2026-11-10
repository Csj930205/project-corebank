package com.corebank;

import com.corebank.support.SchemaOwner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 테스트용 Oracle 컨테이너 설정. compose.yaml 과 같은 이미지 버전, 같은 계정 구성(테이블 소유자 + 앱 계정)을 써서
 * 운영과 비슷한 환경에서 검증한다.
 *
 * <p>컨테이너는 static 으로 하나만 만들어 모든 테스트가 함께 쓴다. 스프링은 설정이 다른 테스트 클래스(@MockitoSpyBean,
 * @AutoConfigureMockMvc 등)마다 컨텍스트를 새로 만드는데, 빈 메서드에서 매번 new 하면 컨텍스트 수만큼 Oracle 이 떠서
 * Docker 자원이 모자라 시작 시간 초과가 난다.
 *
 * <p>계정: 컨테이너 사용자(withUsername)가 테이블 소유자이고, docker/oracle-init/01-create-app-user.sh 가 앱 계정을 만든다.
 * 앱(DataSource)은 앱 계정으로, Flyway 는 소유자로 접속한다. 그래서 테스트가 통과하면 앱 계정 권한(R__app_user_grants.sql)이
 * 모자라지 않다는 것도 함께 확인된다. @ServiceConnection 은 계정을 하나만 연결하므로 쓰지 않고 접속 정보를 직접 넣는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String OWNER_USERNAME = "corebank";
	static final String OWNER_PASSWORD = "owner-test-password";
	static final String APP_USERNAME = "corebank_app";
	static final String APP_PASSWORD = "app-test-password";

	private static final OracleContainer ORACLE =
			new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23.26.3-slim-faststart"))
					.withUsername(OWNER_USERNAME)
					.withPassword(OWNER_PASSWORD)
					.withEnv("DB_APP_USERNAME", APP_USERNAME)
					.withEnv("DB_APP_PASSWORD", APP_PASSWORD)
					.withCopyFileToContainer(
							MountableFile.forHostPath("docker/oracle-init/01-create-app-user.sh", 0755),
							"/container-entrypoint-initdb.d/01-create-app-user.sh");

	static {
		ORACLE.start();
	}

	/** application.properties 가 .env 에서 읽는 값을 컨테이너 값으로 채운다. */
	@Bean
	DynamicPropertyRegistrar oracleProperties() {
		return registry -> {
			registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
			registry.add("DB_OWNER_USERNAME", () -> OWNER_USERNAME);
			registry.add("DB_OWNER_PASSWORD", () -> OWNER_PASSWORD);
			registry.add("DB_APP_USERNAME", () -> APP_USERNAME);
			registry.add("DB_APP_PASSWORD", () -> APP_PASSWORD);
		};
	}

	/** 테이블 소유자 접속. 앱 계정으로는 할 수 없는 일(트리거 끄기, 개설일 바꾸기)을 테스트가 할 때만 쓴다. */
	@Bean
	SchemaOwner schemaOwner() {
		return new SchemaOwner(new JdbcTemplate(
				new SingleConnectionDataSource(ORACLE.getJdbcUrl(), OWNER_USERNAME, OWNER_PASSWORD, true)));
	}

}
