package com.corebank.auth;

import com.corebank.TestcontainersConfiguration;
import com.corebank.admin.AdminRole;
import com.corebank.admin.AdminUser;
import com.corebank.admin.AdminUserService;
import com.corebank.support.MutableClock;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리프레시 토큰과 로그인 세션. 앱 시계(MutableClock)를 옮겨 유예 시간·쓰지 않을 때 만료·12시간 상한을 확인한다.
 *
 * <p>MockMvc 는 브라우저처럼 쿠키를 자동으로 보관하지 않는다. 응답의 Set-Cookie 에서 값을 꺼내 다음 요청에 직접 싣는다.
 */
@Import({TestcontainersConfiguration.class, RefreshTokenIntegrationTest.Clocks.class})
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class Clocks {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}
	}

	private static final String ADMIN = "test-admin";
	private static final String ADMIN_PASSWORD = "test-admin-password-1234";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AdminUserService adminUserService;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	/** 로그인(또는 리프레시) 한 번의 결과: 액세스 토큰과 리프레시 쿠키 값 */
	private record Tokens(String access, String refresh) {
	}

	private Tokens login(String username, String password) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
				.andExpect(status().isOk())
				.andReturn().getResponse();
		return tokens(response);
	}

	private MockHttpServletResponse refreshResponse(String refreshToken) throws Exception {
		return mockMvc.perform(post("/auth/refresh").cookie(new Cookie(RefreshCookie.NAME, refreshToken)))
				.andReturn().getResponse();
	}

	private Tokens refresh(String refreshToken) throws Exception {
		MockHttpServletResponse response = refreshResponse(refreshToken);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return tokens(response);
	}

	private static Tokens tokens(MockHttpServletResponse response) throws Exception {
		Cookie cookie = response.getCookie(RefreshCookie.NAME);
		assertThat(cookie).isNotNull();
		return new Tokens(JsonPath.read(response.getContentAsString(), "$.accessToken"), cookie.getValue());
	}

	private int meStatus(String accessToken) throws Exception {
		return mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andReturn().getResponse().getStatus();
	}

	private static String code(MockHttpServletResponse response) throws Exception {
		return JsonPath.read(response.getContentAsString(), "$.code");
	}

	/** 리프레시 토큰은 본문이 아니라 HttpOnly·SameSite=Strict·Path=/auth 쿠키로만 나간다. DB 에는 원문 대신 해시만 */
	@Test
	void loginSetsHttpOnlyRefreshCookie() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN, ADMIN_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.refreshExpiresAt").isString())
				.andExpect(jsonPath("$.sessionExpiresAt").isString())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andReturn().getResponse();

		String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains(RefreshCookie.NAME + "=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/auth",
				"Max-Age=1800");
		String raw = response.getCookie(RefreshCookie.NAME).getValue();
		assertThat(raw).hasSize(43);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?", Integer.class,
				AuthSessionService.hash(raw))).isOne();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?", Integer.class, raw))
				.isZero();
	}

	/** 리프레시하면 새 액세스 토큰과 새 리프레시 토큰(회전)을 준다. 같은 세션이라 이전 액세스 토큰도 만료 전까지 쓸 수 있다 */
	@Test
	void refreshRotatesToken() throws Exception {
		Tokens first = login(ADMIN, ADMIN_PASSWORD);

		Tokens second = refresh(first.refresh());

		assertThat(second.refresh()).isNotEqualTo(first.refresh());
		assertThat(second.access()).isNotEqualTo(first.access());
		assertThat(meStatus(second.access())).isEqualTo(200);
		assertThat(meStatus(first.access())).isEqualTo(200);
		assertThat(refresh(second.refresh()).access()).isNotBlank();
	}

	/**
	 * 쓴 리프레시 토큰을 유예 시간(10초) 뒤에 다시 내밀면 도난으로 보고 세션을 끊는다. 그 세션의 액세스 토큰·새 리프레시 토큰이
	 * 모두 그 즉시 막힌다(진짜 사용자도 다시 로그인해야 하지만, 비밀번호를 모르는 도둑은 더는 못 쓴다).
	 */
	@Test
	void reusedRefreshTokenRevokesSession() throws Exception {
		Tokens first = login(ADMIN, ADMIN_PASSWORD);
		Tokens second = refresh(first.refresh());
		clock.moveBy(Duration.ofSeconds(30));

		MockHttpServletResponse reuse = refreshResponse(first.refresh());

		assertThat(reuse.getStatus()).isEqualTo(401);
		assertThat(code(reuse)).isEqualTo("REFRESH_TOKEN_REUSED");
		assertThat(reuse.getCookie(RefreshCookie.NAME).getMaxAge()).isZero();
		assertThat(meStatus(second.access())).isEqualTo(401);
		assertThat(code(refreshResponse(second.refresh()))).isEqualTo("INVALID_REFRESH_TOKEN");
		assertThat(jdbc.queryForObject("""
				SELECT s.revoke_reason FROM auth_session s JOIN refresh_token t ON t.session_id = s.id
				WHERE t.token_hash = ?""", String.class, AuthSessionService.hash(first.refresh())))
				.isEqualTo("REUSE_DETECTED");
	}

	/** 유예 시간 안의 재사용(탭 두 개가 거의 동시에 리프레시)은 그 요청만 거부하고 세션은 그대로 둔다 */
	@Test
	void reuseWithinGraceKeepsSession() throws Exception {
		Tokens first = login(ADMIN, ADMIN_PASSWORD);
		Tokens second = refresh(first.refresh());

		MockHttpServletResponse late = refreshResponse(first.refresh());

		assertThat(late.getStatus()).isEqualTo(401);
		assertThat(code(late)).isEqualTo("REFRESH_TOKEN_REUSED");
		assertThat(meStatus(second.access())).isEqualTo(200);
		assertThat(refresh(second.refresh()).access()).isNotBlank();
	}

	/** 같은 토큰으로 동시에 리프레시해도 새 토큰은 하나만 나간다(토큰 행 잠금). 나머지는 유예 시간 안이라 세션은 산다 */
	@Test
	void concurrentRefreshIssuesOnce() throws Exception {
		Tokens first = login(ADMIN, ADMIN_PASSWORD);
		int threads = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MockHttpServletResponse>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				Callable<MockHttpServletResponse> call = () -> {
					start.await();
					return refreshResponse(first.refresh());
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			int ok = 0;
			String winner = null;
			for (Future<MockHttpServletResponse> result : results) {
				MockHttpServletResponse response = result.get();
				if (response.getStatus() == 200) {
					ok++;
					winner = JsonPath.read(response.getContentAsString(), "$.accessToken");
				} else {
					assertThat(code(response)).isEqualTo("REFRESH_TOKEN_REUSED");
				}
			}
			assertThat(ok).isOne();
			assertThat(meStatus(winner)).isEqualTo(200);
		} finally {
			pool.shutdownNow();
		}
	}

	/** 로그아웃하면 쿠키를 지우고, 그 세션의 액세스 토큰(만료 전)과 리프레시 토큰이 바로 막힌다. 다시 해도 204 */
	@Test
	void logoutRevokesSessionImmediately() throws Exception {
		Tokens tokens = login(ADMIN, ADMIN_PASSWORD);
		assertThat(meStatus(tokens.access())).isEqualTo(200);

		MockHttpServletResponse logout = mockMvc.perform(post("/auth/logout")
						.cookie(new Cookie(RefreshCookie.NAME, tokens.refresh())))
				.andExpect(status().isNoContent())
				.andReturn().getResponse();

		assertThat(logout.getCookie(RefreshCookie.NAME).getMaxAge()).isZero();
		assertThat(meStatus(tokens.access())).isEqualTo(401);
		assertThat(code(refreshResponse(tokens.refresh()))).isEqualTo("INVALID_REFRESH_TOKEN");
		mockMvc.perform(post("/auth/logout").cookie(new Cookie(RefreshCookie.NAME, tokens.refresh())))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/auth/logout")).andExpect(status().isNoContent());
		// 다른 로그인 세션은 그대로다(로그아웃은 이 브라우저만).
		assertThat(meStatus(login(ADMIN, ADMIN_PASSWORD).access())).isEqualTo(200);
	}

	@Test
	void refreshWithoutOrWithUnknownCookieIs401() throws Exception {
		mockMvc.perform(post("/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
		assertThat(code(refreshResponse("no-such-token"))).isEqualTo("INVALID_REFRESH_TOKEN");
	}

	/** 30분 넘게 리프레시하지 않으면(쓰지 않음) 리프레시 토큰이 끝난다 */
	@Test
	void idleRefreshTokenExpires() throws Exception {
		Tokens tokens = login(ADMIN, ADMIN_PASSWORD);
		clock.moveBy(Duration.ofMinutes(31));

		assertThat(code(refreshResponse(tokens.refresh()))).isEqualTo("INVALID_REFRESH_TOKEN");
	}

	/** 계속 리프레시해도 로그인 뒤 12시간이면 세션이 끝난다. 액세스 토큰도 그때 막힌다 */
	@Test
	void sessionEndsAfterTwelveHoursEvenWhenActive() throws Exception {
		Tokens tokens = login(ADMIN, ADMIN_PASSWORD);
		// 20분마다 리프레시해 11시간 40분까지 이어 간다.
		for (int i = 0; i < 35; i++) {
			clock.moveBy(Duration.ofMinutes(20));
			tokens = refresh(tokens.refresh());
		}
		// 마지막 리프레시 토큰은 원래 30분 뒤까지지만 세션 끝(12시간)에서 잘린다. 12시간 1분이면 끝난 뒤다.
		clock.moveBy(Duration.ofMinutes(21));

		assertThat(code(refreshResponse(tokens.refresh()))).isEqualTo("INVALID_REFRESH_TOKEN");
		assertThat(meStatus(tokens.access())).isEqualTo(401);
	}

	/** 로그인한 뒤 계정이 로그인할 수 없는 상태가 되면 다음 리프레시에서 세션을 끊는다. 역할은 토큰이 아니라 DB 에서 다시 읽는다 */
	@Test
	void refreshRereadsAccountState() throws Exception {
		String username = "rt" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		String password = "refresh-test-password";
		AdminUser user = adminUserService.request(username, password, AdminRole.TELLER, ADMIN);
		adminUserService.approve(user.getId(), "test-approver");
		Tokens tokens = login(username, password);

		MockHttpServletResponse ok = refreshResponse(tokens.refresh());
		assertThat(ok.getStatus()).isEqualTo(200);
		assertThat((String) JsonPath.read(ok.getContentAsString(), "$.adminRole")).isEqualTo("TELLER");
		Tokens refreshed = tokens(ok);

		jdbc.update("UPDATE admin_user SET status = 'REJECTED' WHERE username = ?", username);
		clock.moveBy(Duration.ofMinutes(1));

		assertThat(code(refreshResponse(refreshed.refresh()))).isEqualTo("INVALID_REFRESH_TOKEN");
		assertThat(meStatus(refreshed.access())).isEqualTo(401);
		assertThat(jdbc.queryForObject("SELECT revoke_reason FROM auth_session WHERE username = ?", String.class,
				username)).isEqualTo("ACCOUNT_INACTIVE");
	}

	/** 고객도 같다. 가입하면 바로 리프레시 쿠키를 받고, 리프레시한 토큰으로 본인 계좌 API 를 쓴다 */
	@Test
	void customerRefreshKeepsCustomerClaims() throws Exception {
		String username = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		MockHttpServletResponse signup = mockMvc.perform(post("/auth/customer/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"password-1234\",\"name\":\"리프레시\"}".formatted(username)))
				.andExpect(status().isCreated())
				.andReturn().getResponse();

		MockHttpServletResponse refreshed = refreshResponse(tokens(signup).refresh());

		assertThat(refreshed.getStatus()).isEqualTo(200);
		assertThat((String) JsonPath.read(refreshed.getContentAsString(), "$.role")).isEqualTo("CUSTOMER");
		assertThat((String) JsonPath.read(refreshed.getContentAsString(), "$.name")).isEqualTo("리프레시");
		mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens(refreshed).access()))
				.andExpect(status().isOk());
	}

	/** 리프레시·로그아웃도 감사 기록에 남는다(토큰 없는 요청이라 아이디는 세션에서 찾아 적는다) */
	@Test
	void refreshAndLogoutAreAudited() throws Exception {
		Tokens tokens = login(ADMIN, ADMIN_PASSWORD);
		Tokens refreshed = refresh(tokens.refresh());
		mockMvc.perform(post("/auth/logout").cookie(new Cookie(RefreshCookie.NAME, refreshed.refresh())))
				.andExpect(status().isNoContent());

		List<String> actions = jdbc.queryForList("""
				SELECT action FROM (SELECT action FROM audit_log WHERE actor_username = ? ORDER BY id DESC)
				WHERE ROWNUM <= 2""", String.class, ADMIN);
		assertThat(actions).containsExactly("LOGOUT", "TOKEN_REFRESH");
	}

}
