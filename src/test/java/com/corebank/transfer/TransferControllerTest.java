package com.corebank.transfer;

import com.corebank.account.AccountClosedException;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.BalanceLimitExceededException;
import com.corebank.account.InsufficientBalanceException;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.auth.SecurityConfig;
import com.corebank.account.AccountAccessPolicy;
import com.corebank.account.AccountService;
import com.corebank.support.AdminByDefault;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이체 웹 계층 테스트. 서비스는 가짜 객체로 바꾸고 HTTP 상태 코드와 JSON 형식만 검증한다.
 */
// 실제 보안 규칙(SecurityConfig)으로 시험한다. 빼면 스프링 기본 규칙(전부 로그인 필요, CSRF 검사)이 적용된다.
@Import({SecurityConfig.class, AdminByDefault.class})
@WebMvcTest(TransferController.class)
class TransferControllerTest {

	private static final IdempotencyKey KEY = new IdempotencyKey("test-key-1");

	private static final String VALID = "{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":1000}";

	@Autowired
	private MockMvc mockMvc;

	// 관리자 토큰이라 권한 확인은 항상 통과한다. 실제 규칙은 CustomerAuthIntegrationTest 에서 본다.
	@MockitoBean
	private AccountAccessPolicy accessPolicy;

	@MockitoBean
	private TransferService transferService;

	@MockitoBean
	private AccountService accountService;

	private org.springframework.test.web.servlet.ResultActions postTransfer(String body) throws Exception {
		return mockMvc.perform(post("/transfers").header(IdempotencyKey.HEADER, KEY.value()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** 정상 이체: 201, Location 헤더, 두 계좌의 이체 후 잔액 */
	@Test
	void transferReturnsCreated() throws Exception {
		Transfer transfer = Transfer.of(1L, 2L, 1000);
		ReflectionTestUtils.setField(transfer, "id", 7L);
		given(transferService.transfer(1L, 2L, 1000L, KEY)).willReturn(IdempotentResult.created(new TransferResult(transfer, 9000, 1000)));

		postTransfer(VALID)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/transfers/7"))
				.andExpect(jsonPath("$.transferId").value(7))
				.andExpect(jsonPath("$.fromAccountId").value(1))
				.andExpect(jsonPath("$.toAccountId").value(2))
				.andExpect(jsonPath("$.amount").value(1000))
				.andExpect(jsonPath("$.fromBalanceAfter").value(9000))
				.andExpect(jsonPath("$.toBalanceAfter").value(1000));
	}

	/** 잘못된 금액, 계좌 누락, 문자 id 는 서비스까지 가지 않고 400 으로 거부된다. */
	@ParameterizedTest
	@ValueSource(strings = {
			"{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":0}",
			"{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":-1}",
			"{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":1.5}",
			"{\"fromAccountId\":1,\"toAccountId\":2}",
			"{\"toAccountId\":2,\"amount\":1000}",
			"{\"fromAccountId\":1,\"amount\":1000}",
			"{\"fromAccountId\":\"abc\",\"toAccountId\":2,\"amount\":1000}",
			// 받는 계좌를 id 와 계좌번호로 동시에 주면 어느 쪽인지 모른다
			"{\"fromAccountId\":1,\"toAccountId\":2,\"toAccountNumber\":\"1100000000002\",\"amount\":1000}",
			// 계좌번호는 숫자 13자리
			"{\"fromAccountId\":1,\"toAccountNumber\":\"110-0000-000002\",\"amount\":1000}",
			"{\"fromAccountId\":1,\"toAccountNumber\":\"123\",\"amount\":1000}",
			"{bad"
	})
	void transferRejectsInvalidRequest(String body) throws Exception {
		postTransfer(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(transferService);
	}

	/** 받는 계좌를 계좌번호로 주면 id 로 바꿔 이체한다. */
	@Test
	void transferByAccountNumber() throws Exception {
		Transfer transfer = Transfer.of(1L, 2L, 1000);
		ReflectionTestUtils.setField(transfer, "id", 8L);
		given(accountService.idOf("1100000000002")).willReturn(2L);
		given(transferService.transfer(1L, 2L, 1000L, KEY)).willReturn(IdempotentResult.created(new TransferResult(transfer, 9000, 1000)));

		postTransfer("{\"fromAccountId\":1,\"toAccountNumber\":\"1100000000002\",\"amount\":1000}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.toAccountId").value(2));
	}

	/** 없는 계좌번호면 이체 서비스까지 가지 않고 404 다. */
	@Test
	void unknownAccountNumberReturnsNotFound() throws Exception {
		given(accountService.idOf("1109999999999")).willThrow(AccountNotFoundException.byNumber("1109999999999"));

		postTransfer("{\"fromAccountId\":1,\"toAccountNumber\":\"1109999999999\",\"amount\":1000}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
		verifyNoInteractions(transferService);
	}

	@Test
	void sameAccountReturnsBadRequest() throws Exception {
		given(transferService.transfer(1L, 1L, 1000L, KEY)).willThrow(new SameAccountTransferException(1L));

		postTransfer("{\"fromAccountId\":1,\"toAccountId\":1,\"amount\":1000}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
	}

	@Test
	void missingAccountReturnsNotFound() throws Exception {
		given(transferService.transfer(1L, 2L, 1000L, KEY)).willThrow(new AccountNotFoundException(2L));

		postTransfer(VALID)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	@Test
	void closedAccountReturnsConflict() throws Exception {
		given(transferService.transfer(1L, 2L, 1000L, KEY)).willThrow(new AccountClosedException(2L));

		postTransfer(VALID)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
	}

	@Test
	void insufficientBalanceReturnsUnprocessable() throws Exception {
		given(transferService.transfer(1L, 2L, 1000L, KEY)).willThrow(new InsufficientBalanceException(1L));

		postTransfer(VALID)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
	}

	@Test
	void balanceLimitReturnsUnprocessable() throws Exception {
		given(transferService.transfer(1L, 2L, 1000L, KEY)).willThrow(new BalanceLimitExceededException(2L));

		postTransfer(VALID)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("BALANCE_LIMIT_EXCEEDED"));
	}

}
