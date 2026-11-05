package com.corebank.idempotency;

import com.corebank.account.RecipientLookupLimit;
import com.corebank.account.AccountController;
import com.corebank.account.AccountService;
import com.corebank.ledger.LedgerTransaction;
import com.corebank.transfer.TransferController;
import com.corebank.transfer.TransferService;
import com.corebank.auth.SecurityConfig;
import com.corebank.account.AccountAccessPolicy;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멱등성 키 헤더의 웹 계층 동작. 입금, 출금, 이체 세 API 모두 같은 규칙을 따르는지 확인한다.
 */
// 실제 보안 규칙(SecurityConfig)으로 시험한다. 빼면 스프링 기본 규칙(전부 로그인 필요, CSRF 검사)이 적용된다.
@Import({SecurityConfig.class, AdminByDefault.class})
@WebMvcTest({AccountController.class, TransferController.class})
class IdempotencyControllerTest {

	@Autowired
	private MockMvc mockMvc;

	// 관리자 토큰이라 권한 확인은 항상 통과한다. 실제 규칙은 CustomerAuthIntegrationTest 에서 본다.
	@MockitoBean
	private AccountAccessPolicy accessPolicy;

	// 수취인 조회 횟수 제한은 DB 가 필요하다. 웹 계층 테스트에서는 아무것도 하지 않는 가짜로 둔다.
	@MockitoBean
	private RecipientLookupLimit lookupLimit;

	@MockitoBean
	private AccountService accountService;

	@MockitoBean
	private TransferService transferService;

	private static final String AMOUNT = "{\"amount\":1000}";
	private static final String TRANSFER = "{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":1000}";

	/** 키가 없으면 세 API 모두 400 IDEMPOTENCY_KEY_MISSING 이고, 서비스까지 가지 않는다. */
	@ParameterizedTest
	@ValueSource(strings = {"/accounts/1/deposits", "/accounts/1/withdrawals", "/transfers"})
	void missingKeyIsRejected(String path) throws Exception {
		mockMvc.perform(post(path)
						.contentType(MediaType.APPLICATION_JSON)
						.content(path.equals("/transfers") ? TRANSFER : AMOUNT))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));

		verifyNoInteractions(accountService, transferService);
	}

	/** 빈 값, 허용하지 않는 문자, 101자는 400 INVALID_IDEMPOTENCY_KEY */
	@ParameterizedTest
	@ValueSource(strings = {"", "has space", "한글키", "a/b", "TOO_LONG"})
	void malformedKeyIsRejected(String key) throws Exception {
		// 애너테이션 값에는 식을 쓸 수 없어서, 101자 키는 여기서 만든다.
		if (key.equals("TOO_LONG")) {
			key = "x".repeat(101);
		}
		mockMvc.perform(post("/accounts/1/deposits")
						.header(IdempotencyKey.HEADER, key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(AMOUNT))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

		verifyNoInteractions(accountService);
	}

	/** UUID 와 영문·숫자·'-'·'_' 조합은 허용한다. */
	@ParameterizedTest
	@ValueSource(strings = {"6f1c2c1e-6c55-4a43-9a43-2f8f6f3b0f11", "order_2026-11-03_0001"})
	void wellFormedKeyIsAccepted(String key) throws Exception {
		given(accountService.deposit(1L, 1000L, new IdempotencyKey(key)))
				.willReturn(IdempotentResult.created(mock(LedgerTransaction.class)));

		mockMvc.perform(post("/accounts/1/deposits")
						.header(IdempotencyKey.HEADER, key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(AMOUNT))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(IdempotentResponses.REPLAYED_HEADER));
	}

	@Test
	void hundredCharacterKeyIsAccepted() throws Exception {
		String key = "k".repeat(100);
		given(accountService.deposit(1L, 1000L, new IdempotencyKey(key)))
				.willReturn(IdempotentResult.created(mock(LedgerTransaction.class)));

		mockMvc.perform(post("/accounts/1/deposits")
						.header(IdempotencyKey.HEADER, key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(AMOUNT))
				.andExpect(status().isCreated());
	}

	/** 다시 보낸 요청도 201 이고, Idempotent-Replayed: true 헤더로만 구분된다. */
	@Test
	void replayHasSameStatusAndReplayedHeader() throws Exception {
		given(accountService.withdraw(anyLong(), anyLong(), any()))
				.willReturn(IdempotentResult.replayed(mock(LedgerTransaction.class)));

		mockMvc.perform(post("/accounts/1/withdrawals")
						.header(IdempotencyKey.HEADER, "retry-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(AMOUNT))
				.andExpect(status().isCreated())
				.andExpect(header().string(IdempotentResponses.REPLAYED_HEADER, "true"));
	}

	/** 같은 키로 다른 요청: 422 IDEMPOTENCY_KEY_REUSED */
	@Test
	void reusedKeyReturnsUnprocessable() throws Exception {
		IdempotencyKey key = new IdempotencyKey("used-key");
		given(transferService.transfer(1L, 2L, 1000L, key)).willThrow(new IdempotencyKeyReusedException(key));

		mockMvc.perform(post("/transfers")
						.header(IdempotencyKey.HEADER, key.value())
						.contentType(MediaType.APPLICATION_JSON)
						.content(TRANSFER))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

}
