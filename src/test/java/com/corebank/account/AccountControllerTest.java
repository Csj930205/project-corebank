package com.corebank.account;

import com.corebank.product.DepositProduct;

import com.corebank.idempotency.IdempotencyKey;
import com.corebank.auth.SecurityConfig;
import com.corebank.support.AdminByDefault;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.ledger.LedgerTransaction;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 계층 테스트. @WebMvcTest 는 컨트롤러 관련 빈만 올리므로 DB와 Docker 없이 빠르게 실행된다.
 * 서비스는 가짜 객체(MockitoBean)로 바꿔, HTTP 상태 코드와 JSON 형식만 검증한다.
 */
// 실제 보안 규칙(SecurityConfig)으로 시험한다. 빼면 스프링 기본 규칙(전부 로그인 필요, CSRF 검사)이 적용된다.
@Import({SecurityConfig.class, AdminByDefault.class})
@WebMvcTest(AccountController.class)
class AccountControllerTest {

	private static final DepositProduct PRODUCT = DepositProduct.of("기본 입출금 통장", new java.math.BigDecimal("0.10"));

	@Autowired
	private MockMvc mockMvc;

	private static final IdempotencyKey KEY = new IdempotencyKey("test-key-1");

	// 관리자 토큰이라 권한 확인은 항상 통과한다. 실제 규칙은 CustomerAuthIntegrationTest 에서 본다.
	@MockitoBean
	private AccountAccessPolicy accessPolicy;

	// 수취인 조회 횟수 제한은 DB 가 필요하다. 웹 계층 테스트에서는 아무것도 하지 않는 가짜로 둔다.
	@MockitoBean
	private RecipientLookupLimit lookupLimit;

	@MockitoBean
	private AccountService accountService;

	/** 정상 개설: 201 과 계좌 정보를 반환한다. */
	@Test
	void openReturnsCreatedAccount() throws Exception {
		given(accountService.open("홍길동", null)).willReturn(Account.open("1100000000001", "홍길동", PRODUCT));

		mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerName\":\"홍길동\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accountNumber").value("1100000000001"))
				.andExpect(jsonPath("$.ownerName").value("홍길동"))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	/** 공백 이름: 400 을 반환하고, 서비스까지 요청이 가지 않는다. */
	@Test
	void openRejectsBlankOwnerName() throws Exception {
		mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ownerName\":\" \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(accountService);
	}

	/** 정상 조회: 200 과 계좌 정보를 반환한다. */
	@Test
	void getReturnsAccount() throws Exception {
		given(accountService.get(1L)).willReturn(Account.open("1100000000001", "홍길동", PRODUCT));

		mockMvc.perform(get("/accounts/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountNumber").value("1100000000001"));
	}

	/** 없는 계좌: 404 와 공통 에러 코드를 반환한다. */
	@Test
	void getReturnsNotFoundForMissingAccount() throws Exception {
		given(accountService.get(999L)).willThrow(new AccountNotFoundException(999L));

		mockMvc.perform(get("/accounts/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	/** 잘못된 경로 값: 공통 에러 형식의 400 을 반환한다. */
	@Test
	void getRejectsNonNumericId() throws Exception {
		mockMvc.perform(get("/accounts/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	/** 깨진 JSON: 공통 에러 형식의 400 을 반환한다. */
	@Test
	void openRejectsMalformedJson() throws Exception {
		mockMvc.perform(post("/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{bad"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	/** 잘못된 입금액(0, 음수, 소수, 문자, 누락)은 서비스까지 가지 않고 400 으로 거부된다. */
	@ParameterizedTest
	@ValueSource(strings = {"{\"amount\":0}", "{\"amount\":-1}", "{\"amount\":1.5}", "{\"amount\":\"abc\"}", "{}"})
	void depositRejectsInvalidAmount(String body) throws Exception {
		mockMvc.perform(post("/accounts/1/deposits").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(accountService);
	}

	/** 해지 계좌 입금: 409 */
	@Test
	void depositReturnsConflictForClosedAccount() throws Exception {
		given(accountService.deposit(1L, 1000L, KEY)).willThrow(new AccountClosedException(1L));

		mockMvc.perform(post("/accounts/1/deposits").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
	}

	/** 잔액 한도 초과: 422 */
	@Test
	void depositReturnsUnprocessableWhenLimitExceeded() throws Exception {
		given(accountService.deposit(1L, 1000L, KEY)).willThrow(new BalanceLimitExceededException(1L));

		mockMvc.perform(post("/accounts/1/deposits").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("BALANCE_LIMIT_EXCEEDED"));
	}

	/** JSON 이 아닌 Content-Type: 공통 형식의 415 와 받을 수 있는 형식(Accept 헤더) */
	@Test
	void rejectsUnsupportedMediaTypeWithCommonFormat() throws Exception {
		mockMvc.perform(post("/accounts/1/deposits").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.TEXT_PLAIN)
						.content("amount=1000"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(header().string("Accept", containsString("application/json")))
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
				.andExpect(jsonPath("$.message").isString());

		verifyNoInteractions(accountService);
	}

	/** 지원하지 않는 메서드: 공통 형식의 405 와 사용 가능한 메서드(Allow 헤더) */
	@Test
	void rejectsUnsupportedMethodWithCommonFormat() throws Exception {
		mockMvc.perform(delete("/accounts/1"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", containsString("GET")))
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
				.andExpect(jsonPath("$.message").isString());
	}

	/** 없는 경로: 공통 형식의 404. 계좌 없음(ACCOUNT_NOT_FOUND)과 코드가 다르다. */
	@Test
	void rejectsUnknownPathWithCommonFormat() throws Exception {
		mockMvc.perform(get("/no-such-path"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").isString());
	}

	/** 거래내역: size 를 생략하면 20 이고, 응답에 목록과 nextBefore 가 담긴다. */
	@Test
	void transactionsUseDefaultSize() throws Exception {
		TransactionHistoryResponse.Item item = new TransactionHistoryResponse.Item(
				5L, com.corebank.ledger.TransactionType.TRANSFER_IN, TransactionHistoryResponse.Direction.IN,
				3000, 13000, 2L, new TransactionHistoryResponse.Counterparty(9L, "1100000000009", "친구"), null,
				java.time.LocalDateTime.of(2026, 11, 3, 10, 0));
		given(accountService.history(1L, null, 20))
				.willReturn(new TransactionHistoryResponse(1L, java.util.List.of(item), 5L));

		mockMvc.perform(get("/accounts/1/transactions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(1))
				.andExpect(jsonPath("$.nextBefore").value(5))
				.andExpect(jsonPath("$.transactions[0].type").value("TRANSFER_IN"))
				.andExpect(jsonPath("$.transactions[0].direction").value("IN"))
				.andExpect(jsonPath("$.transactions[0].amount").value(3000))
				.andExpect(jsonPath("$.transactions[0].balanceAfter").value(13000))
				.andExpect(jsonPath("$.transactions[0].counterparty.ownerName").value("친구"));
	}

	/** before 와 size 는 그대로 서비스에 전달된다. */
	@Test
	void transactionsPassCursorAndSize() throws Exception {
		given(accountService.history(1L, 100L, 5))
				.willReturn(new TransactionHistoryResponse(1L, java.util.List.of(), null));

		mockMvc.perform(get("/accounts/1/transactions").param("before", "100").param("size", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transactions").isEmpty())
				.andExpect(jsonPath("$.nextBefore").doesNotExist());
	}

	/** 잘못된 size(0, 101, 문자)와 before(0, 음수)는 400 */
	@ParameterizedTest
	@ValueSource(strings = {"size=0", "size=101", "size=abc", "before=0", "before=-1", "before=abc"})
	void transactionsRejectInvalidParams(String query) throws Exception {
		mockMvc.perform(get("/accounts/1/transactions?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(accountService);
	}

	@Test
	void transactionsOfMissingAccountReturnNotFound() throws Exception {
		given(accountService.history(999L, null, 20)).willThrow(new AccountNotFoundException(999L));

		mockMvc.perform(get("/accounts/999/transactions"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	/** 잘못된 출금액은 입금과 같은 규칙으로 서비스까지 가지 않고 400 으로 거부된다. */
	@ParameterizedTest
	@ValueSource(strings = {"{\"amount\":0}", "{\"amount\":-1}", "{\"amount\":1.5}", "{\"amount\":\"abc\"}", "{}"})
	void withdrawRejectsInvalidAmount(String body) throws Exception {
		mockMvc.perform(post("/accounts/1/withdrawals").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(accountService);
	}

	/** 잔액 부족: 422 */
	@Test
	void withdrawReturnsUnprocessableWhenBalanceInsufficient() throws Exception {
		given(accountService.withdraw(1L, 1000L, KEY)).willThrow(new InsufficientBalanceException(1L));

		mockMvc.perform(post("/accounts/1/withdrawals").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
	}

	/** 해지 계좌 출금: 409 */
	@Test
	void withdrawReturnsConflictForClosedAccount() throws Exception {
		given(accountService.withdraw(1L, 1000L, KEY)).willThrow(new AccountClosedException(1L));

		mockMvc.perform(post("/accounts/1/withdrawals").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
	}

	/** 없는 계좌 출금: 404 */
	@Test
	void withdrawReturnsNotFoundForMissingAccount() throws Exception {
		given(accountService.withdraw(999L, 1000L, KEY)).willThrow(new AccountNotFoundException(999L));

		mockMvc.perform(post("/accounts/999/withdrawals").header(IdempotencyKey.HEADER, KEY.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1000}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	/** 잔액 10,000원을 지급하고 해지된 계좌. 서비스가 돌려줄 결과를 만든다. */
	private static AccountClosure closureWithPayout() {
		Account account = Account.open("1100000000001", "홍길동", PRODUCT);
		ReflectionTestUtils.setField(account, "id", 1L);
		account.deposit(10_000);
		account.close();
		LedgerTransaction payout = mock(LedgerTransaction.class);
		given(payout.getId()).willReturn(55L);
		given(payout.getAmount()).willReturn(10_000L);
		return new AccountClosure(account, payout);
	}

	/** 정상 해지: 200 과 지급액, CLOSED 상태를 반환한다. 처음 요청이라 재전송 헤더가 없다. */
	@Test
	void closeReturnsOkWithPayout() throws Exception {
		// 목 객체를 만드는 도우미는 given(...) 밖에서 먼저 불러야 한다. 안에서 부르면 스터빙이 겹쳐 Mockito 오류가 난다.
		AccountClosure closure = closureWithPayout();
		given(accountService.close(1L, KEY)).willReturn(IdempotentResult.created(closure));

		mockMvc.perform(post("/accounts/1/closure").header(IdempotencyKey.HEADER, KEY.value()))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.accountId").value(1))
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.payoutAmount").value(10_000))
				.andExpect(jsonPath("$.payoutTransactionId").value(55))
				.andExpect(jsonPath("$.closedAt").exists());
	}

	/** 같은 키 재전송: 같은 200 과 본문에 Idempotent-Replayed 헤더만 붙는다. */
	@Test
	void closeReplayReturnsSameOkWithReplayedHeader() throws Exception {
		// 목 객체를 만드는 도우미는 given(...) 밖에서 먼저 불러야 한다. 안에서 부르면 스터빙이 겹쳐 Mockito 오류가 난다.
		AccountClosure closure = closureWithPayout();
		given(accountService.close(1L, KEY)).willReturn(IdempotentResult.replayed(closure));

		mockMvc.perform(post("/accounts/1/closure").header(IdempotencyKey.HEADER, KEY.value()))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.payoutTransactionId").value(55));
	}

	/** 잔액 0 해지: 지급액 0, 지급 거래 ID 는 null 이다. */
	@Test
	void closeWithZeroBalanceReturnsNoPayout() throws Exception {
		Account account = Account.open("1100000000001", "홍길동", PRODUCT);
		account.close();
		given(accountService.close(1L, KEY)).willReturn(IdempotentResult.created(new AccountClosure(account, null)));

		mockMvc.perform(post("/accounts/1/closure").header(IdempotencyKey.HEADER, KEY.value()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payoutAmount").value(0))
				.andExpect(jsonPath("$.payoutTransactionId").doesNotExist());
	}

	/** 해지도 돈이 나가므로 멱등성 키가 필수다. */
	@Test
	void closeRequiresIdempotencyKey() throws Exception {
		mockMvc.perform(post("/accounts/1/closure"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));

		verifyNoInteractions(accountService);
	}

	/** 이미 해지된 계좌: 409 */
	@Test
	void closeReturnsConflictForClosedAccount() throws Exception {
		given(accountService.close(1L, KEY)).willThrow(new AccountClosedException(1L));

		mockMvc.perform(post("/accounts/1/closure").header(IdempotencyKey.HEADER, KEY.value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
	}

	/** 없는 계좌 해지: 404 */
	@Test
	void closeReturnsNotFoundForMissingAccount() throws Exception {
		given(accountService.close(999L, KEY)).willThrow(new AccountNotFoundException(999L));

		mockMvc.perform(post("/accounts/999/closure").header(IdempotencyKey.HEADER, KEY.value()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

}
