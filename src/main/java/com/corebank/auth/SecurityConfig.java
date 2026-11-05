package com.corebank.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증·인가 규칙.
 *
 * <ul>
 *   <li>/auth/login, /auth/customer/** : 누구나(로그인, 가입)</li>
 *   <li>/admin/** : 관리자 토큰(scope=ADMIN)</li>
 *   <li>GET /accounts (내 계좌 목록) : 고객 토큰(scope=CUSTOMER)</li>
 *   <li>/accounts/**, /transfers/** : 관리자 또는 고객. 고객이 본인 계좌만 다루는지는 컨트롤러가
 *       AccountAccessPolicy 로 확인한다. 어떤 계좌인지는 요청 내용을 봐야 알 수 있어 URL 규칙만으로는 못 정한다.</li>
 *   <li>/auth/me : 로그인한 사용자</li>
 *   <li>그 밖 : 누구나. 없는 경로는 404 RESOURCE_NOT_FOUND 로 응답하기 위해서다.</li>
 * </ul>
 *
 * <p>세션을 만들지 않고(STATELESS) 요청마다 Authorization: Bearer 토큰으로 판단한다.
 * 토큰을 쿠키가 아니라 헤더로 보내므로 브라우저가 자동으로 실어 보내지 않고, 그래서 CSRF 방어가 필요 없다.
 */
@Configuration(proxyBeanMethods = false)
@Import(JwtConfig.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, e) ->
				writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요하거나 토큰이 유효하지 않습니다.", true);
		AccessDeniedHandler forbidden = (request, response, e) ->
				writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "권한이 없습니다.", false);

		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/auth/login", "/auth/customer/signup", "/auth/customer/login")
						.permitAll()
						.requestMatchers("/admin/**").hasAuthority("SCOPE_ADMIN")
						.requestMatchers(HttpMethod.GET, "/accounts").hasAuthority("SCOPE_CUSTOMER")
						.requestMatchers("/accounts/**", "/transfers/**").hasAnyAuthority("SCOPE_ADMIN", "SCOPE_CUSTOMER")
						.requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
						.anyRequest().permitAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> {
						})
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler(forbidden))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler(forbidden));
		return http.build();
	}

	/**
	 * 보안 필터에서 난 오류도 API 공통 에러 형식({code, message})으로 응답한다.
	 * 필터는 컨트롤러보다 앞에서 동작해 ApiExceptionHandler 가 잡지 못하므로 여기서 직접 쓴다.
	 * 메시지는 고정 문자열이라 JSON 이스케이프가 필요 없다.
	 */
	private static void writeError(HttpServletResponse response, int status, String code, String message,
			boolean bearerChallenge) throws IOException {
		response.setStatus(status);
		if (bearerChallenge) {
			// RFC 6750: 401 응답에는 어떤 인증 방식이 필요한지 알려 준다.
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

}
