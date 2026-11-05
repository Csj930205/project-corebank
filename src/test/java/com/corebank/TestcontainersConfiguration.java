package com.corebank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Oracle 컨테이너 설정. compose.yaml 과 같은 이미지 버전을 써서 운영과 비슷한 환경에서 검증한다.
 *
 * <p>컨테이너는 static 으로 하나만 만들어 모든 테스트가 함께 쓴다. 스프링은 설정이 다른 테스트 클래스(@MockitoSpyBean,
 * @AutoConfigureMockMvc 등)마다 컨텍스트를 새로 만드는데, 빈 메서드에서 매번 new 하면 컨텍스트 수만큼 Oracle 이 떠서
 * Docker 자원이 모자라 시작 시간 초과가 난다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final OracleContainer ORACLE =
			new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23.26.3-slim-faststart"));

	// @ServiceConnection: 컨테이너의 접속 정보를 스프링 DataSource 설정에 자동으로 연결한다.
	@Bean
	@ServiceConnection
	OracleContainer oracleFreeContainer() {
		return ORACLE;
	}

}
