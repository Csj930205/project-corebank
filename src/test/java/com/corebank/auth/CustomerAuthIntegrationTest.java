package com.corebank.auth;

import com.corebank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.corebank.admin.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고객 가입·로그인과 계좌 권한. 실제 보안 필터와 Oracle 위에서 HTTP 요청으로 확인한다.
 *
 * <p>핵심 규칙: 고객은 본인 계좌만 조회·거래할 수 있고, 남의 계좌는 있어도 404 로 숨긴다. 관리자는 모든 계좌를 다룬다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CustomerAuthIntegrationTest {

	private static final String PASSWORD = "customer-password-1";

	@Autowired
	private AuthSessionService tokenService;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 테스트마다 겹치지 않는 아이디 */
	private static String username() {
		return "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private ResultActions signUp(String username, String password, String name) throws Exception {
		return mockMvc.perform(post("/auth/customer/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"%s\",\"password\":\"%s\",\"name\":\"%s\"}".formatted(username, password, name)));
	}

	/** 가입하고 토큰을 받는다. */
	private String newCustomer(String name) throws Exception {
		String body = signUp(username(), PASSWORD, name).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	/** 창구 관리자 토큰. 고객 계좌를 대신 다루는 것은 창구 역할이다. */
	private String adminToken() {
		return tokenService.issueAdminToken("test-teller", AdminRole.TELLER).value();
	}

	/** 토큰 주인으로 계좌를 열고 id 를 돌려준다. */
	private long openAccount(String token) throws Exception {
		String body = mockMvc.perform(post("/accounts")
						.header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private ResultActions deposit(String token, long accountId, long amount) throws Exception {
		return mockMvc.perform(post("/accounts/" + accountId + "/deposits")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + "}"));
	}

	private long balance(long accountId) {
		return jdbcTemplate.queryForObject("SELECT balance FROM account WHERE id = ?", Long.class, accountId);
	}

	// ───────────────────────── 가입·로그인 ─────────────────────────

	/** 가입하면 바로 고객 토큰을 준다. 비밀번호는 BCrypt 해시로만 저장한다. */
	@Test
	void signUpReturnsCustomerTokenAndStoresHash() throws Exception {
		String username = username();

		signUp(username, PASSWORD, "홍길동")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isString())
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(jsonPath("$.name").value("홍길동"));

		String hash = jdbcTemplate.queryForObject(
				"SELECT password_hash FROM customer WHERE username = ?", String.class, username);
		assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
	}

	@Test
	void duplicateUsernameIsRejected() throws Exception {
		String username = username();
		signUp(username, PASSWORD, "처음").andExpect(status().isCreated());

		signUp(username, PASSWORD, "두번째")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
	}

	/** 아이디는 영문 소문자로 시작하는 4~30자, 비밀번호는 10자 이상 */
	@Test
	void invalidSignUpIsRejected() throws Exception {
		signUp("Abc12345", PASSWORD, "대문자").andExpect(status().isBadRequest());
		signUp("ab", PASSWORD, "짧은아이디").andExpect(status().isBadRequest());
		signUp(username(), "short-pw", "짧은비번").andExpect(status().isBadRequest());
		signUp(username(), PASSWORD, " ").andExpect(status().isBadRequest());
	}

	@Test
	void customerLogin() throws Exception {
		String username = username();
		signUp(username, PASSWORD, "로그인").andExpect(status().isCreated());

		mockMvc.perform(post("/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("CUSTOMER"));
		mockMvc.perform(post("/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	/** 로그인 창구는 하나다. 같은 /auth/login 으로 고객은 고객 토큰을, 관리자는 관리자 토큰을 받는다. */
	@Test
	void sharedLoginTellsRole() throws Exception {
		String username = username();
		signUp(username, PASSWORD, "한창구").andExpect(status().isCreated());

		String body = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(jsonPath("$.name").value("한창구"))
				.andReturn().getResponse().getContentAsString();
		String customerToken = JsonPath.read(body, "$.accessToken");
		mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(customerToken)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/admin/summary").header(HttpHeaders.AUTHORIZATION, bearer(customerToken)))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"test-admin\",\"password\":\"test-admin-password-1234\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.name").doesNotExist());
		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	/** 관리자 아이디로는 가입할 수 없다. 받아 주면 그 고객은 공용 로그인에서 관리자로만 확인돼 로그인할 수 없다. */
	@Test
	void adminUsernameCannotBeUsedForSignUp() throws Exception {
		String adminName = username();
		jdbcTemplate.update("INSERT INTO admin_user (id, username, password_hash, created_at, role, status) "
				+ "VALUES (admin_user_seq.NEXTVAL, ?, 'not-a-real-hash', SYSTIMESTAMP, 'TELLER', 'ACTIVE')", adminName);

		signUp(adminName, PASSWORD, "흉내").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
	}

	/** 관리자 계정으로는 고객 로그인을 할 수 없다. 두 계정은 따로다. */
	@Test
	void adminCannotLogInAsCustomer() throws Exception {
		mockMvc.perform(post("/auth/customer/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"test-admin\",\"password\":\"test-admin-password-1234\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void meShowsCustomer() throws Exception {
		String token = newCustomer("나고객");

		mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(jsonPath("$.name").value("나고객"))
				.andExpect(jsonPath("$.customerId").isNumber());
	}

	// ───────────────────────── 본인 계좌 ─────────────────────────

	/** 고객이 연 계좌는 본인 명의다. 요청의 예금주 이름은 무시한다(남의 이름으로 열 수 없다). */
	@Test
	void customerOpensAccountInOwnName() throws Exception {
		String token = newCustomer("김예금");

		mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerName\":\"다른사람\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ownerName").value("김예금"))
				.andExpect(jsonPath("$.customerId").isNumber());
	}

	/** 내 계좌 목록에는 본인 계좌만, 최신 개설순으로 나온다. */
	@Test
	void listShowsOnlyOwnAccounts() throws Exception {
		String me = newCustomer("목록나");
		String other = newCustomer("목록남");
		long first = openAccount(me);
		long second = openAccount(me);
		openAccount(other);

		mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts.length()").value(2))
				.andExpect(jsonPath("$.accounts[0].id").value(second))
				.andExpect(jsonPath("$.accounts[1].id").value(first));
	}

	@Test
	void customerTransactsOnOwnAccount() throws Exception {
		String token = newCustomer("내돈");
		long id = openAccount(token);

		deposit(token, id, 10_000).andExpect(status().isCreated());
		mockMvc.perform(get("/accounts/" + id + "/transactions").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transactions.length()").value(1));
		assertThat(balance(id)).isEqualTo(10_000);
	}

	// ───────────────────────── 남의 계좌 ─────────────────────────

	/**
	 * 남의 계좌는 조회, 거래내역, 입금, 출금, 해지 모두 404 다. 403 이 아닌 이유는 계좌가 있다는 사실도 숨기기 위해서다.
	 * 돈은 한 푼도 움직이지 않는다.
	 */
	@Test
	void otherCustomersAccountIsHidden() throws Exception {
		String owner = newCustomer("주인");
		String intruder = newCustomer("남");
		long id = openAccount(owner);
		deposit(owner, id, 10_000).andExpect(status().isCreated());
		String auth = bearer(intruder);

		mockMvc.perform(get("/accounts/" + id).header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
		mockMvc.perform(get("/accounts/" + id + "/transactions").header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isNotFound());
		deposit(intruder, id, 1_000).andExpect(status().isNotFound());
		mockMvc.perform(post("/accounts/" + id + "/withdrawals").header(HttpHeaders.AUTHORIZATION, auth)
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/accounts/" + id + "/closure").header(HttpHeaders.AUTHORIZATION, auth)
						.header("Idempotency-Key", UUID.randomUUID().toString()))
				.andExpect(status().isNotFound());

		assertThat(balance(id)).isEqualTo(10_000);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, id))
				.isEqualTo("ACTIVE");
	}

	/** 관리자가 이름으로만 연(주인 없는) 계좌도 고객에게는 남의 계좌다. */
	@Test
	void accountWithoutOwnerIsHiddenFromCustomers() throws Exception {
		String admin = adminToken();
		String body = mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(admin))
						.contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"창구개설\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.customerId").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		mockMvc.perform(get("/accounts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(newCustomer("아무개"))))
				.andExpect(status().isNotFound());
	}

	/** 이체는 본인 계좌에서 남의 계좌로 보낼 수 있다. 남의 계좌에서 빼 오는 것은 404 이고 돈이 움직이지 않는다. */
	@Test
	void transferOnlyFromOwnAccount() throws Exception {
		String me = newCustomer("보내는나");
		String other = newCustomer("받는남");
		long mine = openAccount(me);
		long theirs = openAccount(other);
		deposit(me, mine, 10_000).andExpect(status().isCreated());
		deposit(other, theirs, 10_000).andExpect(status().isCreated());

		mockMvc.perform(post("/transfers").header(HttpHeaders.AUTHORIZATION, bearer(me))
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":3000}".formatted(mine, theirs)))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/transfers").header(HttpHeaders.AUTHORIZATION, bearer(me))
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amount\":5000}".formatted(theirs, mine)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

		assertThat(balance(mine)).isEqualTo(7_000);
		assertThat(balance(theirs)).isEqualTo(13_000);
	}

	// ───────────────────────── 수취인 조회 ─────────────────────────

	private String accountNumber(long accountId) {
		return jdbcTemplate.queryForObject("SELECT account_number FROM account WHERE id = ?", String.class, accountId);
	}

	private ResultActions lookup(String token, String accountNumber) throws Exception {
		return mockMvc.perform(get("/accounts/lookup").param("accountNumber", accountNumber)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)));
	}

	/** 남의 계좌도 계좌번호로 조회되지만, 가린 예금주 이름만 나오고 id·잔액은 나오지 않는다. */
	@Test
	void lookupShowsMaskedOwnerOnly() throws Exception {
		String me = newCustomer("보내는이");
		String other = newCustomer("홍길동");
		long theirs = openAccount(other);
		deposit(other, theirs, 50_000).andExpect(status().isCreated());
		String number = accountNumber(theirs);

		lookup(me, number)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountNumber").value(number))
				.andExpect(jsonPath("$.ownerName").value("홍*동"))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.balance").doesNotExist());
	}

	@Test
	void lookupRejectsUnknownClosedAndMalformed() throws Exception {
		String me = newCustomer("조회자");
		long closed = openAccount(me);
		mockMvc.perform(post("/accounts/" + closed + "/closure").header(HttpHeaders.AUTHORIZATION, bearer(me))
						.header("Idempotency-Key", UUID.randomUUID().toString()))
				.andExpect(status().isOk());

		lookup(me, "1109999999999").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
		lookup(me, accountNumber(closed)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
		lookup(me, "110-0000-000001").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mockMvc.perform(get("/accounts/lookup").param("accountNumber", accountNumber(closed)))
				.andExpect(status().isUnauthorized());
	}

	/** 고객은 받는 계좌를 계좌번호로 정해 이체한다. 남의 계좌 id 를 몰라도 된다. */
	@Test
	void customerTransfersByAccountNumber() throws Exception {
		String me = newCustomer("번호로보냄");
		String other = newCustomer("번호로받음");
		long mine = openAccount(me);
		long theirs = openAccount(other);
		deposit(me, mine, 10_000).andExpect(status().isCreated());

		mockMvc.perform(post("/transfers").header(HttpHeaders.AUTHORIZATION, bearer(me))
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":%d,\"toAccountNumber\":\"%s\",\"amount\":4000}"
								.formatted(mine, accountNumber(theirs))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.toAccountId").value(theirs))
				.andExpect(jsonPath("$.fromBalanceAfter").value(6_000))
				// 남의 계좌 잔액은 고객에게 주지 않는다
				.andExpect(jsonPath("$.toBalanceAfter").value(org.hamcrest.Matchers.nullValue()));

		assertThat(balance(mine)).isEqualTo(6_000);
		assertThat(balance(theirs)).isEqualTo(4_000);
	}

	// ───────────────────────── 관리자, 비로그인 ─────────────────────────

	/** 관리자는 고객 계좌도 조회하고 거래할 수 있다(창구 업무). 내 계좌 목록(GET /accounts)은 고객 전용이다. */
	@Test
	void adminCanHandleCustomerAccounts() throws Exception {
		String customer = newCustomer("창구손님");
		long id = openAccount(customer);
		String admin = adminToken();

		mockMvc.perform(get("/accounts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerName").value("창구손님"));
		deposit(admin, id, 5_000).andExpect(status().isCreated());
		mockMvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isForbidden());
		assertThat(balance(id)).isEqualTo(5_000);
	}

	/** 관리자가 계좌를 열 때는 예금주 이름이 필요하다. */
	@Test
	void adminOpenRequiresOwnerName() throws Exception {
		mockMvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void anonymousIsRejected() throws Exception {
		mockMvc.perform(get("/accounts/1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"익명\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":1}"))
				.andExpect(status().isUnauthorized());
	}

	/** 고객 토큰으로는 관리자 API 를 쓸 수 없다. */
	@Test
	void customerCannotUseAdminApi() throws Exception {
		mockMvc.perform(get("/admin/summary").header(HttpHeaders.AUTHORIZATION, bearer(newCustomer("관리자흉내"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

}
