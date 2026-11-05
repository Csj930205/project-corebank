package com.corebank.ratelimit;

import com.corebank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * 로그인 잠금과 수취인 조회 횟수 제한. 실제 보안 필터와 Oracle 위에서 HTTP 요청으로 확인한다.
 *
 * <p>한도를 작게(로그인 3번, 조회 3번) 두고, 시계를 앞으로 돌려 잠금 만료와 다음 구간을 확인한다.
 * 토큰 검증은 실제 시각을 쓰므로 시계는 실제 시각보다 앞으로만 돌린다(뒤로 가면 방금 받은 토큰이 만료된 것처럼 보인다).
 */
@Import({TestcontainersConfiguration.class, RateLimitIntegrationTest.MovableClockConfig.class})
@SpringBootTest(properties = {
		"corebank.login.max-failures=3",
		"corebank.login.lock-duration=10m",
		"corebank.rate-limit.recipient-lookup.max=3",
		"corebank.rate-limit.recipient-lookup.window=1h"})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

	private static final String PASSWORD = "rate-limit-password-1234";

	/** 앞으로 돌릴 수 있는 시계 */
	static class MovableClock extends Clock {

		private final Clock base = Clock.systemDefaultZone();
		private volatile Duration offset = Duration.ZERO;

		void set(Duration offset) {
			this.offset = offset;
		}

		void advance(Duration duration) {
			this.offset = offset.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return base.getZone();
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return base.instant().plus(offset);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MovableClockConfig {

		@Bean
		@Primary
		MovableClock movableClock() {
			return new MovableClock();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MovableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * 시계를 다음 정시 15분 뒤로 맞춘다. 조회 한도는 1시간 구간(정시~정시)이라, 테스트 도중 정시를 넘으면 횟수가 0으로
	 * 돌아가 결과가 흔들린다. 구간 한가운데쯤에서 시작하면 그럴 일이 없다. 실제 시각보다 늘 앞이다.
	 */
	@BeforeEach
	void moveClockToQuarterPast() {
		clock.set(Duration.ZERO);
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime target = now.truncatedTo(ChronoUnit.HOURS).plusHours(1).plusMinutes(15);
		clock.set(Duration.between(now, target));
	}

	// ───────────────────────── 로그인 잠금 ─────────────────────────

	/** 3번 틀리면 잠기고, 그 뒤에는 맞는 비밀번호도 거부한다. Retry-After 로 언제 풀리는지 알려 준다. */
	@Test
	void locksAfterMaxFailuresEvenForCorrectPassword() throws Exception {
		String username = signUp();
		for (int i = 0; i < 3; i++) {
			login(username, "wrong-password").andExpect(status().isUnauthorized());
		}

		MvcResult result = login(username, PASSWORD)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LOGIN_LOCKED"))
				.andExpect(jsonPath("$.message").value("로그인 실패가 너무 많아 잠겼습니다. 10분 뒤에 다시 시도하세요."))
				.andReturn();
		// 잠근 뒤 이 요청까지 실제 시간이 조금 흘러 600초보다 약간 적을 수 있다.
		assertThat(retryAfter(result)).isBetween(590L, 600L);
	}

	/** 없는 아이디도 똑같이 잠긴다. 잠기는지 아닌지로 계정이 있는지 알 수 없다. */
	@Test
	void unknownUsernameLocksTheSameWay() throws Exception {
		String username = "nobody_" + suffix();
		for (int i = 0; i < 3; i++) {
			login(username, "whatever-password").andExpect(status().isUnauthorized());
		}

		login(username, "whatever-password")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
	}

	/** 성공하면 실패 횟수가 처음부터다. "연속" 실패만 센다. */
	@Test
	void successResetsFailureCount() throws Exception {
		String username = signUp();
		login(username, "wrong-password").andExpect(status().isUnauthorized());
		login(username, "wrong-password").andExpect(status().isUnauthorized());
		login(username, PASSWORD).andExpect(status().isOk());

		login(username, "wrong-password").andExpect(status().isUnauthorized());
		login(username, "wrong-password").andExpect(status().isUnauthorized());
		login(username, PASSWORD).andExpect(status().isOk());
	}

	/** 고객 전용 로그인 주소와 공용 주소는 같은 횟수를 쓴다. 주소를 바꿔 가며 시도해도 늘어나지 않는다. */
	@Test
	void bothLoginEndpointsShareTheCount() throws Exception {
		String username = signUp();
		login(username, "wrong-password").andExpect(status().isUnauthorized());
		login(username, "wrong-password").andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/customer/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(credentials(username, "wrong-password")))
				.andExpect(status().isUnauthorized());

		login(username, PASSWORD).andExpect(status().isTooManyRequests());
	}

	/** 잠금 시간이 지나면 다시 로그인된다. */
	@Test
	void lockExpires() throws Exception {
		String username = signUp();
		for (int i = 0; i < 3; i++) {
			login(username, "wrong-password").andExpect(status().isUnauthorized());
		}
		login(username, PASSWORD).andExpect(status().isTooManyRequests());

		clock.advance(Duration.ofMinutes(10).plusSeconds(1));

		login(username, PASSWORD).andExpect(status().isOk());
	}

	/** 관리자가 풀면 잠금 시간을 기다리지 않고 바로 로그인된다. 고객은 풀 수 없다. */
	@Test
	void adminCanUnlock() throws Exception {
		String username = signUp();
		String otherCustomer = JsonPath.read(signUpBody("other_" + suffix()), "$.accessToken");
		for (int i = 0; i < 3; i++) {
			login(username, "wrong-password").andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/admin/login-locks/{username}/unlock", username)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherCustomer))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/login-locks/{username}/unlock", username)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
				.andExpect(status().isNoContent());

		login(username, PASSWORD).andExpect(status().isOk());
	}

	/** 잠긴 아이디는 관리자 목록에 실패 수·풀리는 시각과 함께 나오고, 풀면 빠진다. 고객은 목록을 볼 수 없다. */
	@Test
	void adminSeesLockedLoginsUntilUnlocked() throws Exception {
		String username = signUp();
		String notLockedYet = signUp();
		for (int i = 0; i < 3; i++) {
			login(username, "wrong-password").andExpect(status().isUnauthorized());
		}
		login(notLockedYet, "wrong-password").andExpect(status().isUnauthorized());
		String admin = adminToken();
		String lockedPath = "$.content[?(@.username == '" + username + "')]";

		mockMvc.perform(get("/admin/login-locks").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath(lockedPath + ".failureCount").value(3))
				.andExpect(jsonPath(lockedPath + ".lockedUntil").exists())
				// 실패가 있어도 아직 안 잠긴 아이디는 나오지 않는다
				.andExpect(jsonPath("$.content[?(@.username == '" + notLockedYet + "')]").isEmpty());

		String customer = JsonPath.read(signUpBody("viewer_" + suffix()), "$.accessToken");
		mockMvc.perform(get("/admin/login-locks").header(HttpHeaders.AUTHORIZATION, "Bearer " + customer))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/login-locks/{username}/unlock", username)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/admin/login-locks").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(jsonPath(lockedPath).isEmpty());
	}

	/** 잠금 시간이 지난 아이디는 목록에서 빠진다(행이 남아 있어도). */
	@Test
	void expiredLockIsNotListed() throws Exception {
		String username = signUp();
		for (int i = 0; i < 3; i++) {
			login(username, "wrong-password").andExpect(status().isUnauthorized());
		}

		clock.advance(Duration.ofMinutes(10).plusSeconds(1));

		mockMvc.perform(get("/admin/login-locks").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.username == '" + username + "')]").isEmpty());
	}

	/**
	 * 동시에 10번 틀린 비밀번호를 보내도 비밀번호 확인은 한도(3번)까지만 한다. 나머지는 확인 없이 잠금으로 거부된다.
	 * 확인 "뒤에" 세면 10개가 모두 "아직 0번" 을 보고 통과해 10번을 시도할 수 있다.
	 */
	@Test
	void concurrentAttemptsCannotExceedLimit() throws Exception {
		String username = signUp();
		int threads = 10;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<Integer>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				Callable<Integer> attempt = () -> {
					start.await();
					return login(username, "wrong-password").andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(attempt));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}

			assertThat(statuses).filteredOn(s -> s == 401).hasSize(3);
			assertThat(statuses).filteredOn(s -> s == 429).hasSize(7);
		} finally {
			pool.shutdownNow();
		}
	}

	/** 막힌 로그인도 감사 로그에 거부(DENIED)로 남는다. */
	@Test
	void lockedLoginIsAuditedAsDenied() throws Exception {
		String username = signUp();
		for (int i = 0; i < 3; i++) {
			login(username, "wrong-password").andExpect(status().isUnauthorized());
		}

		MvcResult result = login(username, PASSWORD).andExpect(status().isTooManyRequests()).andReturn();

		String requestId = result.getResponse().getHeader("X-Request-Id");
		assertThat(jdbcTemplate.queryForMap(
				"SELECT outcome, error_code, actor_username FROM audit_log WHERE request_id = ?", requestId))
				.containsEntry("OUTCOME", "DENIED")
				.containsEntry("ERROR_CODE", "LOGIN_LOCKED")
				.containsEntry("ACTOR_USERNAME", username);
	}

	// ───────────────────────── 수취인 조회 ─────────────────────────

	/** 고객은 구간마다 3번까지. 없는 계좌번호(404)도 한 번으로 센다. 다음 구간이 되면 다시 된다. */
	@Test
	void customerLookupIsLimitedPerWindow() throws Exception {
		String customer = JsonPath.read(signUpBody("lookup_" + suffix()), "$.accessToken");
		for (int i = 0; i < 3; i++) {
			lookup(customer).andExpect(status().isNotFound());
		}

		MvcResult result = lookup(customer)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.message").value("요청이 너무 많습니다. 45분 뒤에 다시 시도하세요."))
				.andReturn();
		// 시계가 정시 15분 뒤에서 시작해 이번 구간은 45분(2700초) 남았다. 테스트가 도는 동안 흐른 시간만큼 적을 수 있다.
		assertThat(retryAfter(result)).isBetween(2690L, 2700L);

		clock.advance(Duration.ofHours(1));
		lookup(customer).andExpect(status().isNotFound());
	}

	/** 고객마다 따로 센다. 한 고객이 다 써도 다른 고객은 조회된다. */
	@Test
	void lookupLimitIsPerCustomer() throws Exception {
		String first = JsonPath.read(signUpBody("lookup_" + suffix()), "$.accessToken");
		String second = JsonPath.read(signUpBody("lookup_" + suffix()), "$.accessToken");
		for (int i = 0; i < 3; i++) {
			lookup(first).andExpect(status().isNotFound());
		}
		lookup(first).andExpect(status().isTooManyRequests());

		lookup(second).andExpect(status().isNotFound());
	}

	/** 관리자(창구)는 묶지 않는다. */
	@Test
	void adminLookupIsNotLimited() throws Exception {
		String admin = adminToken();
		for (int i = 0; i < 5; i++) {
			lookup(admin).andExpect(status().isNotFound());
		}
	}

	// ───────────────────────── 도우미 ─────────────────────────

	private ResultActions login(String username, String password) throws Exception {
		return mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(credentials(username, password)));
	}

	private ResultActions lookup(String token) throws Exception {
		return mockMvc.perform(get("/accounts/lookup")
				.param("accountNumber", "0000000000000")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static long retryAfter(MvcResult result) {
		return Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
	}

	private static String credentials(String username, String password) {
		return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
	}

	private static String suffix() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	/** 가입하고 아이디를 돌려준다. */
	private String signUp() throws Exception {
		String username = "rl_" + suffix();
		signUpBody(username);
		return username;
	}

	/** 가입하고 응답 본문(토큰 포함)을 돌려준다. */
	private String signUpBody(String username) throws Exception {
		return mockMvc.perform(post("/auth/customer/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"%s\",\"password\":\"%s\",\"name\":\"횟수제한\"}".formatted(username, PASSWORD)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private String adminToken() throws Exception {
		String body = login("test-admin", "test-admin-password-1234").andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

}
