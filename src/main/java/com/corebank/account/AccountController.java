package com.corebank.account;

import com.corebank.audit.AuditContext;
import com.corebank.auth.Actor;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotentResponses;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.ledger.LedgerTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 계좌 REST API. HTTP 요청과 응답 변환만 맡고 업무 처리는 AccountService 에 넘긴다.
 *
 * <p>모든 요청은 로그인이 필요하다(SecurityConfig). 특정 계좌를 다루는 요청은 업무 처리 전에
 * AccountAccessPolicy 로 "관리자이거나 본인 계좌인지" 확인한다.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

	private final AccountService accountService;
	private final AccountAccessPolicy accessPolicy;

	public AccountController(AccountService accountService, AccountAccessPolicy accessPolicy) {
		this.accountService = accountService;
		this.accessPolicy = accessPolicy;
	}

	/** 내 계좌 목록 응답 */
	public record MyAccounts(List<AccountResponse> accounts) {
	}

	/**
	 * POST /accounts: 계좌 개설. 201 Created 와 함께 Location 헤더로 새 계좌의 주소를 알려 준다.
	 * 고객이 열면 본인 명의이고, 관리자가 열면 요청의 ownerName 으로 주인 없는 계좌를 연다.
	 */
	@PostMapping
	public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		Actor actor = Actor.from(jwt);
		Account account;
		if (actor.isCustomer()) {
			account = accountService.openForCustomer(actor.customerId());
		} else {
			if (!request.hasOwnerName()) {
				throw new OwnerNameRequiredException();
			}
			account = accountService.open(request.ownerName());
		}
		// 새 계좌 번호는 경로에 없으니 감사 기록에 직접 적는다.
		AuditContext.accounts(account.getId(), null);
		return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
				.body(AccountResponse.from(account));
	}

	/** GET /accounts: 로그인한 고객의 계좌 목록(최신 개설순). 고객 토큰만 쓸 수 있다(관리자는 /admin/accounts). */
	@GetMapping
	public MyAccounts mine(@AuthenticationPrincipal Jwt jwt) {
		Actor actor = Actor.from(jwt);
		return new MyAccounts(accountService.listByCustomer(actor.customerId()).stream()
				.map(AccountResponse::from)
				.toList());
	}

	/**
	 * GET /accounts/lookup?accountNumber=: 수취인 조회. 이체 전에 받는 계좌가 있는지, 받을 수 있는지, 예금주가 맞는지
	 * 확인한다. 받는 계좌는 보통 남의 계좌라 본인 확인을 하지 않는 대신, 가린 예금주 이름만 준다.
	 * 없으면 404 ACCOUNT_NOT_FOUND, 해지 계좌면 409 ACCOUNT_CLOSED, 형식이 틀리면 400.
	 *
	 * <p>/accounts/{id} 보다 먼저 맞춰진다(스프링은 고정 경로를 변수 경로보다 우선한다).
	 */
	@GetMapping("/lookup")
	public RecipientResponse lookup(
			@RequestParam @Pattern(regexp = "\\d{13}", message = "계좌번호는 숫자 13자리입니다.") String accountNumber) {
		return RecipientResponse.from(accountService.findRecipient(accountNumber));
	}

	/** GET /accounts/{id}: 계좌 조회. 없거나 남의 계좌면 404. */
	@GetMapping("/{id}")
	public AccountResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		return AccountResponse.from(accountService.get(id));
	}

	/**
	 * GET /accounts/{id}/transactions: 거래내역. 최신순이고, 다음 페이지는 응답의 nextBefore 를 before 에 넣어 요청한다.
	 *
	 * <p>size 는 1~100. @Min/@Max 는 스프링의 메서드 검증이 확인하고, 실패하면 400 INVALID_REQUEST 가 된다.
	 */
	@GetMapping("/{id}/transactions")
	public TransactionHistoryResponse transactions(
			@PathVariable Long id,
			@RequestParam(required = false) @Positive Long before,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		return accountService.history(id, before, size);
	}

	/**
	 * POST /accounts/{id}/deposits: 입금. 거래 기록이 새로 생기므로 201 로 응답한다.
	 * Idempotency-Key 헤더가 필수이고, 같은 키로 다시 보내면 처음 결과를 같은 201 로 돌려준다.
	 */
	@PostMapping("/{id}/deposits")
	public ResponseEntity<DepositResponse> deposit(
			@RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
			@PathVariable Long id,
			@Valid @RequestBody DepositRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		IdempotentResult<LedgerTransaction> result =
				accountService.deposit(id, request.amount(), new IdempotencyKey(idempotencyKey));
		return IdempotentResponses.created(result, DepositResponse.from(result.value()));
	}

	/** POST /accounts/{id}/withdrawals: 출금. 입금과 같은 규칙으로 201 과 멱등성 키를 다룬다. */
	@PostMapping("/{id}/withdrawals")
	public ResponseEntity<WithdrawalResponse> withdraw(
			@RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
			@PathVariable Long id,
			@Valid @RequestBody WithdrawalRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		IdempotentResult<LedgerTransaction> result =
				accountService.withdraw(id, request.amount(), new IdempotencyKey(idempotencyKey));
		return IdempotentResponses.created(result, WithdrawalResponse.from(result.value()));
	}

	/**
	 * POST /accounts/{id}/closure: 계좌 해지. 남은 잔액을 지급하고 계좌를 CLOSED 로 바꾼다.
	 *
	 * <p>DELETE 를 쓰지 않는 이유: 해지해도 계좌와 거래내역은 지워지지 않고 GET 으로 계속 조회된다.
	 * 새 자원을 만들지 않고 기존 계좌의 상태를 바꾸므로 200 으로 응답한다. 돈이 나가므로 멱등성 키가 필수다.
	 */
	@PostMapping("/{id}/closure")
	public ResponseEntity<AccountClosureResponse> close(
			@RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
			@PathVariable Long id,
			@AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		IdempotentResult<AccountClosure> result = accountService.close(id, new IdempotencyKey(idempotencyKey));
		return IdempotentResponses.ok(result, AccountClosureResponse.from(result.value()));
	}

}
