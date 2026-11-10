package com.corebank.migration;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * DB 마이그레이션 전용 실행 진입점. 테이블 소유자 계정으로 Flyway 마이그레이션만 하고 끝난다.
 *
 * <p>서버(ProjectCorebankApplication)는 앱 계정만 갖고, 마이그레이션은 하지 않고 확인만 한다(SchemaVersionCheck).
 * 그래서 소유자 비밀번호는 이 프로그램을 실행하는 곳(배포 단계)에만 있으면 된다.
 *
 * <p>설정은 application.properties 가 아니라 migration.properties 를 읽는다. 웹 서버, JPA, 보안, 배치는 띄우지 않고
 * DataSource 와 Flyway 만 켠다.
 *
 * <p>실행: 개발은 {@code ./gradlew migrate}, 배포는 서버와 같은 jar 로
 * {@code java -cp app.jar -Dloader.main=com.corebank.migration.MigrationApplication org.springframework.boot.loader.launch.PropertiesLauncher}.
 */
public final class MigrationApplication {

	private MigrationApplication() {
	}

	public static void main(String[] args) {
		migrate(args);
	}

	/** 마이그레이션하고 돌아온다. 실패하면 예외를 던진다(main 에서는 종료 코드 1). 테스트도 이 메서드로 DB 를 준비한다. */
	public static void migrate(String... args) {
		SpringApplication application = new SpringApplication(MigrationConfig.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setBannerMode(Banner.Mode.OFF);
		application.setDefaultProperties(Map.of("spring.config.name", "migration"));
		try (ConfigurableApplicationContext ignored = application.run(args)) {
			// FlywayAutoConfiguration 이 시작하면서 마이그레이션을 마친다. 할 일이 끝났으니 바로 닫는다.
		}
	}

	/**
	 * DataSource 와 Flyway 자동 설정만 켠다. @Configuration 을 붙이지 않아 서버의 컴포넌트 스캔에는 잡히지 않는다
	 * (@ImportAutoConfiguration 만으로도 설정 클래스로 처리된다).
	 */
	@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
	static class MigrationConfig {
	}

}
