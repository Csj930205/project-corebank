package com.corebank.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 웹 계층 테스트(@WebMvcTest)의 모든 요청에 창구(TELLER) 관리자 토큰을 붙인다. 계좌·이체 변경은 창구 역할이 필요하다.
 *
 * <p>계좌·이체 API 는 로그인이 필요하다. 이 테스트들은 HTTP 형식(상태 코드, JSON)을 보는 것이 목적이라
 * 요청마다 토큰을 붙이지 않고 기본값으로 둔다. 권한 규칙(고객은 본인 계좌만)은 CustomerAuthIntegrationTest 가 본다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AdminByDefault {

	@Bean
	MockMvcBuilderCustomizer adminTokenByDefault() {
		return builder -> builder.defaultRequest(get("/").with(jwt()
				.jwt(j -> j.subject("test-teller").claim("scope", "ADMIN").claim("role", "TELLER"))
				.authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"), new SimpleGrantedAuthority("ROLE_TELLER"))));
	}

}
