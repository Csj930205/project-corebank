package com.corebank.auth;

import com.corebank.admin.AdminRole;
import com.corebank.audit.AuditContext;
import com.corebank.audit.AuditLogFilter;
import com.corebank.audit.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.oauth2.server.resource.authentication.DelegatingJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증·인가 규칙. 위에서부터 처음 맞는 규칙을 쓴다.
 *
 * <ul>
 *   <li>/auth/login, /auth/customer/** : 누구나(로그인, 가입)</li>
 *   <li>/admin/** 조회(GET) : 모든 관리자(scope=ADMIN)</li>
 *   <li>/admin/** 변경 : 맡은 역할만(토큰의 role). 아래 목록에 없는 변경은 막는다.
 *     <ul>
 *       <li>창구(TELLER): 거래 정정 요청</li>
 *       <li>책임자(APPROVER): 거래 정정·관리자 등록의 승인과 반려</li>
 *       <li>운영(OPERATOR): 일 마감, 이자, 상품, 로그인 잠금 해제, 관리자 등록 요청</li>
 *     </ul></li>
 *   <li>GET /accounts (내 계좌 목록) : 고객</li>
 *   <li>/accounts/**, /transfers 조회 : 관리자 또는 고객. 변경(개설·입출금·해지·이체) : 창구 또는 고객.
 *       고객이 본인 계좌만 다루는지는 컨트롤러가 AccountAccessPolicy 로 확인한다. 어떤 계좌인지는 요청 내용을 봐야
 *       알 수 있어 URL 규칙만으로는 못 정한다.</li>
 *   <li>/auth/me, GET /products : 로그인한 사용자</li>
 *   <li>그 밖 : 누구나. 없는 경로는 404 RESOURCE_NOT_FOUND 로 응답하기 위해서다.</li>
 * </ul>
 *
 * <p>왜 역할 규칙을 컨트롤러(@PreAuthorize)가 아니라 여기 한곳에 모으나: "누가 무엇을 바꿀 수 있나"를 이 표 하나로
 * 검토할 수 있어서다. 그리고 관리자 변경은 목록에 없으면 막는다(denyAll). 새 API 를 만들고 규칙을 잊으면
 * 모든 관리자에게 열리는 대신 아무에게도 열리지 않아, 실수가 테스트에서 바로 드러난다.
 *
 * <p>세션을 만들지 않고(STATELESS) 요청마다 Authorization: Bearer 토큰으로 판단한다.
 * 토큰을 쿠키가 아니라 헤더로 보내므로 브라우저가 자동으로 실어 보내지 않고, 그래서 CSRF 방어가 필요 없다.
 *
 * <p>감사 로그 필터(AuditLogFilter)를 토큰 확인 바로 앞에 끼운다. 자리를 정한 이유는 그 클래스 주석 참고.
 */
@Configuration(proxyBeanMethods = false)
@Import(JwtConfig.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<AuditLogRepository> auditLogRepository)
			throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, e) ->
				writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요하거나 토큰이 유효하지 않습니다.", true);
		AccessDeniedHandler forbidden = (request, response, e) ->
				writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "권한이 없습니다.", false);

		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/auth/login", "/auth/customer/signup", "/auth/customer/login")
						.permitAll()
						// 관리자 변경: 역할별
						.requestMatchers(HttpMethod.POST, "/admin/corrections").hasRole(AdminRole.TELLER.name())
						.requestMatchers(HttpMethod.POST, "/admin/corrections/*/approval", "/admin/corrections/*/rejection",
								"/admin/admins/*/approval", "/admin/admins/*/rejection")
						.hasRole(AdminRole.APPROVER.name())
						.requestMatchers(HttpMethod.POST, "/admin/closings", "/admin/interest/**", "/admin/products",
								"/admin/products/**", "/admin/login-locks/**", "/admin/admins")
						.hasRole(AdminRole.OPERATOR.name())
						// 관리자 조회: 모든 관리자. 나머지 변경은 막는다.
						.requestMatchers(HttpMethod.GET, "/admin/**").hasAuthority("SCOPE_ADMIN")
						.requestMatchers("/admin/**").denyAll()
						// 계좌·이체
						.requestMatchers(HttpMethod.GET, "/accounts").hasAuthority("SCOPE_CUSTOMER")
						.requestMatchers(HttpMethod.POST, "/accounts/**", "/transfers/**")
						.hasAnyAuthority("SCOPE_CUSTOMER", "ROLE_" + AdminRole.TELLER.name())
						.requestMatchers("/accounts/**", "/transfers/**").hasAnyAuthority("SCOPE_ADMIN", "SCOPE_CUSTOMER")
						.requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
						.requestMatchers(HttpMethod.GET, "/products").hasAnyAuthority("SCOPE_ADMIN", "SCOPE_CUSTOMER")
						.anyRequest().permitAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter()))
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler(forbidden))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler(forbidden));
		// 빈으로 등록하지 않고 여기서 만든다. 빈이면 스프링 부트가 서블릿 필터로도 등록해 요청마다 두 번 기록된다.
		// 웹 계층 테스트(@WebMvcTest)처럼 DB 가 없는 환경에서는 저장소 빈이 없으니 감사 로그 없이 동작한다.
		AuditLogRepository repository = auditLogRepository.getIfAvailable();
		if (repository != null) {
			http.addFilterBefore(new AuditLogFilter(repository), BearerTokenAuthenticationFilter.class);
		}
		return http.build();
	}

	/**
	 * 토큰을 권한 목록으로 바꾼다. scope claim 은 "SCOPE_ADMIN"(누구인가), role claim 은 "ROLE_TELLER"(무슨 일을 맡았나).
	 * 기본 변환기는 scope 만 읽으므로, role 을 읽는 변환기를 하나 더 붙인다. hasRole("TELLER") 는 "ROLE_TELLER" 를 찾는다.
	 */
	static JwtAuthenticationConverter authenticationConverter() {
		JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName(Actor.ROLE_CLAIM);
		roles.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(
				new DelegatingJwtGrantedAuthoritiesConverter(new JwtGrantedAuthoritiesConverter(), roles));
		return converter;
	}

	/**
	 * 보안 필터에서 난 오류도 API 공통 에러 형식({code, message})으로 응답한다.
	 * 필터는 컨트롤러보다 앞에서 동작해 ApiExceptionHandler 가 잡지 못하므로 여기서 직접 쓴다.
	 * 메시지는 고정 문자열이라 JSON 이스케이프가 필요 없다.
	 */
	private static void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code,
			String message, boolean bearerChallenge) throws IOException {
		AuditContext.errorCode(request, code);
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
