package com.corebank.audit;

import com.corebank.TestcontainersConfiguration;
import com.corebank.support.SchemaOwner;
import com.jayway.jsonpath.JsonPath;
import com.corebank.admin.AdminRole;
import com.corebank.auth.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 감사 로그. 실제 보안 필터와 Oracle 위에서 HTTP 요청을 보내고, 응답 헤더 X-Request-Id 로 그 요청의 기록 한 줄을 찾아 확인한다.
 * 다른 테스트도 같은 DB 에 기록을 남기므로 건수가 아니라 요청 번호로 찾는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogIntegrationTest {

	private static final String ADMIN = "test-admin";
	private static final String ADMIN_PASSWORD = "test-admin-password-1234";
	/** 돈을 움직이는 요청은 창구 역할이 한다. */
	private static final String TELLER = "test-teller";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SchemaOwner owner;

	@Autowired
	private AuthSessionService tokenService;

	// ───────────────────────── 돈이 움직이는 요청 ─────────────────────────

	/** 성공한 입금: 누가(관리자), 무엇을(DEPOSIT), 어느 계좌에, 결과(201 SUCCESS) */
	@Test
	void recordsSuccessfulDeposit() throws Exception {
		String admin = adminToken();
		long accountId = openAccount(admin);

		MvcResult result = deposit(admin, accountId, 5_000).andExpect(status().isCreated()).andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("DEPOSIT");
		assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
		assertThat(row.get("ACTOR_USERNAME")).isEqualTo(TELLER);
		assertThat(number(row, "TARGET_ACCOUNT_ID")).isEqualTo(accountId);
		assertThat(number(row, "STATUS_CODE")).isEqualTo(201);
		assertThat(row.get("OUTCOME")).isEqualTo("SUCCESS");
		assertThat(row.get("ERROR_CODE")).isNull();
		assertThat(row.get("PATH")).isEqualTo("/accounts/" + accountId + "/deposits");
		assertThat(row.get("HTTP_METHOD")).isEqualTo("POST");
	}

	/** 업무 오류로 롤백된 출금도 기록은 남는다. 에러 코드까지 남아 왜 실패했는지 알 수 있다. */
	@Test
	void recordsRolledBackWithdrawalWithErrorCode() throws Exception {
		String admin = adminToken();
		long accountId = openAccount(admin);

		MvcResult result = mockMvc.perform(post("/accounts/{id}/withdrawals", accountId)
						.header(HttpHeaders.AUTHORIZATION, bearer(admin))
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isUnprocessableContent())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("WITHDRAWAL");
		assertThat(row.get("OUTCOME")).isEqualTo("FAILURE");
		assertThat(row.get("ERROR_CODE")).isEqualTo("INSUFFICIENT_BALANCE");
		assertThat(number(row, "TARGET_ACCOUNT_ID")).isEqualTo(accountId);
	}

	/** 이체는 계좌가 본문에 있다. 실패해도 보내는 계좌와 받는 계좌가 함께 남는다. */
	@Test
	void recordsTransferAccountsEvenOnFailure() throws Exception {
		String admin = adminToken();
		long from = openAccount(admin);
		long to = openAccount(admin);

		MvcResult result = mockMvc.perform(post("/transfers")
						.header(HttpHeaders.AUTHORIZATION, bearer(admin))
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":1000}".formatted(from, to)))
				.andExpect(status().isUnprocessableContent())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("TRANSFER");
		assertThat(number(row, "TARGET_ACCOUNT_ID")).isEqualTo(from);
		assertThat(number(row, "COUNTERPART_ACCOUNT_ID")).isEqualTo(to);
		assertThat(row.get("ERROR_CODE")).isEqualTo("INSUFFICIENT_BALANCE");
	}

	// ───────────────────────── 인증·권한 ─────────────────────────

	/** 로그인 실패: 토큰이 없으니 ANONYMOUS 이고, 시도한 아이디가 남는다. 비밀번호는 어디에도 남지 않는다. */
	@Test
	void recordsFailedLoginWithAttemptedUsernameButNoPassword() throws Exception {
		String password = "wrong-" + UUID.randomUUID();
		MvcResult result = mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + ADMIN + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isUnauthorized())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("LOGIN");
		assertThat(row.get("ACTOR_TYPE")).isEqualTo("ANONYMOUS");
		assertThat(row.get("ACTOR_USERNAME")).isEqualTo(ADMIN);
		assertThat(row.get("OUTCOME")).isEqualTo("DENIED");
		assertThat(row.get("ERROR_CODE")).isEqualTo("INVALID_CREDENTIALS");
		assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains(password));
	}

	/** 토큰 없이 계좌 조회: 컨트롤러까지 가지 않고 보안 필터가 막지만 기록은 남는다. */
	@Test
	void recordsRequestRejectedBySecurityFilter() throws Exception {
		MvcResult result = mockMvc.perform(get("/accounts/{id}", 987_654_321L))
				.andExpect(status().isUnauthorized())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_VIEW");
		assertThat(row.get("ACTOR_TYPE")).isEqualTo("ANONYMOUS");
		assertThat(row.get("OUTCOME")).isEqualTo("DENIED");
		assertThat(row.get("ERROR_CODE")).isEqualTo("UNAUTHORIZED");
		assertThat(number(row, "TARGET_ACCOUNT_ID")).isEqualTo(987_654_321L);
	}

	/** 위조·무효 토큰도 토큰 확인 전에 기록을 시작하므로 남는다. */
	@Test
	void recordsInvalidToken() throws Exception {
		MvcResult result = mockMvc.perform(get("/admin/summary").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("ADMIN_SUMMARY");
		assertThat(row.get("OUTCOME")).isEqualTo("DENIED");
	}

	/** 고객이 관리자 API 를 부르면 403. 누가 시도했는지(고객 아이디, 고객 번호)가 남는다. */
	@Test
	void recordsCustomerTryingAdminApi() throws Exception {
		String username = "audit_" + UUID.randomUUID().toString().substring(0, 8);
		String customer = signUp(username);

		MvcResult result = mockMvc.perform(get("/admin/accounts").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
				.andExpect(status().isForbidden())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("ADMIN_ACCOUNT_SEARCH");
		assertThat(row.get("ACTOR_TYPE")).isEqualTo("CUSTOMER");
		assertThat(row.get("ACTOR_USERNAME")).isEqualTo(username);
		assertThat(row.get("CUSTOMER_ID")).isNotNull();
		assertThat(row.get("ERROR_CODE")).isEqualTo("FORBIDDEN");
	}

	/** 가입은 성공해도 토큰 없이 들어온 요청이라 ANONYMOUS, 아이디는 가입한 아이디 */
	@Test
	void recordsSignUp() throws Exception {
		String username = "audit_" + UUID.randomUUID().toString().substring(0, 8);
		MvcResult result = mockMvc.perform(post("/auth/customer/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(signUpBody(username)))
				.andExpect(status().isCreated())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("CUSTOMER_SIGNUP");
		assertThat(row.get("ACTOR_USERNAME")).isEqualTo(username);
		assertThat(row.get("OUTCOME")).isEqualTo("SUCCESS");
	}

	// ───────────────────────── 조회 ─────────────────────────

	/** 수취인 조회처럼 남의 정보를 보는 요청도 남는다. 쿼리 문자열(조회한 계좌번호)까지 남긴다. */
	@Test
	void recordsLookupWithQueryString() throws Exception {
		// .param() 은 MockMvc 에서 쿼리 문자열을 만들지 않아, 실제 요청처럼 주소에 붙여 보낸다.
		MvcResult result = mockMvc.perform(get("/accounts/lookup?accountNumber=0000000000000")
						.header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
				.andExpect(status().isNotFound())
				.andReturn();

		Map<String, Object> row = rowOf(result);
		assertThat(row.get("ACTION")).isEqualTo("RECIPIENT_LOOKUP");
		assertThat(row.get("PATH")).isEqualTo("/accounts/lookup?accountNumber=0000000000000");
		assertThat(row.get("TARGET_ACCOUNT_ID")).isNull();
	}

	/** 남길 대상이 아닌 요청(내 정보 확인)에는 요청 번호도 기록도 없다. */
	@Test
	void skipsUnauditedRequests() throws Exception {
		mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(AuditLogFilter.REQUEST_ID_HEADER));
	}

	// ───────────────────────── 조회 API ─────────────────────────

	/** 계좌로 찾으면 그 계좌의 기록이 최신순으로 나온다. 조회한 것 자체도 기록된다. */
	@Test
	void searchesByAccountNewestFirstAndAuditsTheSearch() throws Exception {
		String admin = adminToken();
		long accountId = openAccount(admin);
		deposit(admin, accountId, 1_000).andExpect(status().isCreated());
		mockMvc.perform(get("/accounts/{id}", accountId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(get("/admin/audit-logs")
						.param("accountId", String.valueOf(accountId))
						.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].action").value("ACCOUNT_VIEW"))
				.andExpect(jsonPath("$.content[1].action").value("DEPOSIT"))
				.andExpect(jsonPath("$.content[2].action").value("ACCOUNT_OPEN"))
				.andExpect(jsonPath("$.content[1].actorUsername").value(TELLER))
				.andReturn();

		assertThat(rowOf(result).get("ACTION")).isEqualTo("AUDIT_LOG_SEARCH");
	}

	/** 조건을 겹치면 모두 만족하는 것만. 없는 action 은 400. */
	@Test
	void searchCombinesFiltersAndRejectsUnknownAction() throws Exception {
		String admin = adminToken();
		long accountId = openAccount(admin);
		deposit(admin, accountId, 1_000).andExpect(status().isCreated());

		mockMvc.perform(get("/admin/audit-logs")
						.param("accountId", String.valueOf(accountId))
						.param("action", "DEPOSIT")
						.param("outcome", "SUCCESS")
						.param("actor", TELLER)
						.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));

		mockMvc.perform(get("/admin/audit-logs")
						.param("action", "NO_SUCH_ACTION")
						.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	// ───────────────────────── 위변조 방지 ─────────────────────────

	/**
	 * 한 번 쓴 기록은 고치거나 지울 수 없다. 앱 계정은 UPDATE·DELETE 권한이 없어(ORA-41900) 막히고,
	 * 권한이 있는 테이블 소유자도 트리거(ORA-20001)에 막힌다.
	 */
	@Test
	void auditLogIsAppendOnly() throws Exception {
		MvcResult result = mockMvc.perform(get("/accounts/{id}", 1L)).andReturn();
		String requestId = result.getResponse().getHeader(AuditLogFilter.REQUEST_ID_HEADER);
		String update = "UPDATE audit_log SET outcome = 'SUCCESS' WHERE request_id = ?";
		String delete = "DELETE FROM audit_log WHERE request_id = ?";

		assertThatThrownBy(() -> jdbcTemplate.update(update, requestId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-41900");
		assertThatThrownBy(() -> jdbcTemplate.update(delete, requestId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-41900");
		assertThatThrownBy(() -> owner.jdbc().update(update, requestId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-20001");
		assertThatThrownBy(() -> owner.jdbc().update(delete, requestId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-20001");
		assertThat(rowOf(result).get("OUTCOME")).isEqualTo("DENIED");
	}

	// ───────────────────────── 도우미 ─────────────────────────

	/** 응답의 X-Request-Id 로 그 요청의 감사 기록 한 줄을 읽는다. */
	private Map<String, Object> rowOf(MvcResult result) {
		String requestId = result.getResponse().getHeader(AuditLogFilter.REQUEST_ID_HEADER);
		assertThat(requestId).as("X-Request-Id 응답 헤더").isNotNull();
		return jdbcTemplate.queryForMap("SELECT * FROM audit_log WHERE request_id = ?", requestId);
	}

	private static long number(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

	/** 기간 끝이 마지막 날(9999-12-31)이어도 된다(전에는 다음 날이 10000년이라 Oracle 이 거절해 500). 그 너머는 400. */
	@Test
	void periodAcceptsLastDateAndRejectsBeyond() throws Exception {
		String admin = adminToken();
		mockMvc.perform(get("/admin/audit-logs?from=0001-01-01&to=9999-12-31").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/audit-logs?to=%2B10000-01-01").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	/** 창구 토큰. 로그인 과정은 로그인 기록 테스트가 따로 본다. */
	private String adminToken() {
		return tokenService.issueAdminToken(TELLER, AdminRole.TELLER).value();
	}

	private static String signUpBody(String username) {
		return "{\"username\":\"%s\",\"password\":\"audit-password-1234\",\"name\":\"감사고객\"}".formatted(username);
	}

	private String signUp(String username) throws Exception {
		String body = mockMvc.perform(post("/auth/customer/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(signUpBody(username)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private long openAccount(String token) throws Exception {
		String body = mockMvc.perform(post("/accounts")
						.header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerName\":\"감사테스트\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private ResultActions deposit(String token, long accountId, long amount) throws Exception {
		return mockMvc.perform(post("/accounts/{id}/deposits", accountId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + "}"));
	}

}
