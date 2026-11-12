package com.corebank.auth;

import com.corebank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.corebank.admin.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 로그인과 토큰 검증. 실제 보안 필터와 Oracle 위에서 HTTP 요청으로 확인한다.
 * 관리자 계정은 테스트 설정(application-default.properties)의 test-admin 으로 서버 시작 시 만들어진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

	private static final String USERNAME = "test-admin";
	private static final String PASSWORD = "test-admin-password-1234";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private String login(String username, String password) throws Exception {
		String body = mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	/** 서버 시작 시 관리자가 만들어지고, 비밀번호는 원문이 아니라 BCrypt 해시로 저장된다. */
	@Test
	void bootstrapStoresHashedPassword() {
		String hash = jdbcTemplate.queryForObject(
				"SELECT password_hash FROM admin_user WHERE username = ?", String.class, USERNAME);

		assertThat(hash).startsWith("$2").hasSize(60).doesNotContain(PASSWORD);
	}

	@Test
	void loginReturnsBearerToken() throws Exception {
		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(1800))
				.andExpect(jsonPath("$.username").value(USERNAME))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.accessToken").isString());
	}

	/** 비밀번호가 틀려도, 아이디가 없어도 같은 401 과 같은 문장이다. 아이디 존재 여부를 알려 주지 않는다. */
	@Test
	void wrongPasswordAndUnknownUserLookTheSame() throws Exception {
		for (String[] pair : new String[][]{{USERNAME, "wrong-password"}, {"nobody", PASSWORD}}) {
			mockMvc.perform(post("/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"" + pair[0] + "\",\"password\":\"" + pair[1] + "\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
					.andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
		}
	}

	@Test
	void blankCredentialsAreRejected() throws Exception {
		mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	/** 토큰 없이 관리자 API: 401 + 공통 에러 형식 + WWW-Authenticate: Bearer */
	@Test
	void adminApiRequiresToken() throws Exception {
		mockMvc.perform(get("/admin/accounts"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void validTokenOpensAdminApiAndMe() throws Exception {
		String token = login(USERNAME, PASSWORD);

		mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray());
		mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(USERNAME))
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	/** 만료된 토큰은 401. 시계를 2시간 전으로 돌려 발급한 토큰(30분짜리)으로 시험한다. */
	@Test
	void expiredTokenIsRejected() throws Exception {
		Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
		String expired = new JwtTokenService(jwtEncoder, twoHoursAgo).issueAdminToken(USERNAME, AdminRole.OPERATOR).value();

		mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(expired)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	/** 다른 키로 서명한(위조한) 토큰은 401. 내용이 관리자여도 서명이 맞지 않으면 믿지 않는다. */
	@Test
	void tokenSignedWithOtherKeyIsRejected() throws Exception {
		byte[] otherKey = "attacker-key-attacker-key-attacker-key!!".getBytes(StandardCharsets.UTF_8);
		JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
		String forged = encode(forger, JwtTokenService.ISSUER, "ADMIN");

		mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
				.andExpect(status().isUnauthorized());
	}

	/** 우리 키로 서명했어도 발급자(iss)가 다르면 401 */
	@Test
	void tokenWithOtherIssuerIsRejected() throws Exception {
		String token = encode(jwtEncoder, "someone-else", "ADMIN");

		mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isUnauthorized());
	}

	/** 유효한 토큰이어도 관리자 권한(scope=ADMIN)이 없으면 403 */
	@Test
	void tokenWithoutAdminScopeIsForbidden() throws Exception {
		String token = encode(jwtEncoder, JwtTokenService.ISSUER, "CUSTOMER");

		mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void malformedTokenIsRejected() throws Exception {
		mockMvc.perform(get("/admin/summary").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * 고객 인증을 만든 뒤로 계좌 API 는 토큰이 있어야 한다(전에는 누구나 쓸 수 있었다).
	 * 서명은 맞아도 우리가 정하지 않은 권한(scope)의 토큰은 403 이다.
	 */
	@Test
	void accountApisRequireAdminOrCustomerToken() throws Exception {
		mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerName\":\"인증없이\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/accounts/999999999")
						.header(HttpHeaders.AUTHORIZATION, bearer(encode(jwtEncoder, JwtTokenService.ISSUER, "TELLER"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(get("/accounts/999999999")
						.header(HttpHeaders.AUTHORIZATION, bearer(login(USERNAME, PASSWORD))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	private static String encode(JwtEncoder encoder, String issuer, String scope) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject(USERNAME)
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(10)))
				.claim("scope", scope)
				.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

}
