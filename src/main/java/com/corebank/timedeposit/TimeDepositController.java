package com.corebank.timedeposit;

import com.corebank.account.AccountAccessPolicy;
import com.corebank.audit.AuditContext;
import com.corebank.auth.Actor;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotentResponses;
import com.corebank.idempotency.IdempotentResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 정기예금 API.
 *
 * <p>가입은 새 자원을 만들므로 POST /time-deposits(201)이고, 정기예금도 계좌라서 조회·해지는 계좌 경로 아래에 둔다
 * (/accounts/{id}/time-deposit). 그래서 본인 계좌 확인(AccountAccessPolicy)과 감사 기록의 대상 계좌가 다른 계좌 API 와 같다.
 */
@RestController
public class TimeDepositController {

	private final TimeDepositService service;
	private final AccountAccessPolicy accessPolicy;

	public TimeDepositController(TimeDepositService service, AccountAccessPolicy accessPolicy) {
		this.service = service;
		this.accessPolicy = accessPolicy;
	}

	/**
	 * @param productId       정기예금 상품
	 * @param principal       원금(원). 연결계좌에서 꺼낸다
	 * @param payoutAccountId 연결계좌. 원금을 꺼내고 만기·중도해지 때 원리금을 받는다. 고객은 본인 입출금 계좌만
	 */
	public record OpenRequest(@NotNull @Positive Long productId, @Positive long principal,
			@NotNull @Positive Long payoutAccountId) {
	}

	public record Page(List<TimeDepositResponse> content, int page, int size, long totalElements, int totalPages) {
	}

	/** POST /time-deposits: 가입. Idempotency-Key 필수. 201, Location 은 정기예금 상세 */
	@PostMapping("/time-deposits")
	public ResponseEntity<TimeDepositResponse> open(
			@RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
			@Valid @RequestBody OpenRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		// 남의 계좌를 연결계좌로 쓰면 그 사람 돈으로 내 정기예금을 드는 셈이다. 남의 계좌는 없는 계좌처럼 404 다.
		accessPolicy.checkAccess(Actor.from(jwt), request.payoutAccountId());
		IdempotentResult<TimeDeposit> result = service.open(request.productId(), request.principal(),
				request.payoutAccountId(), new IdempotencyKey(idempotencyKey));
		Long accountId = result.value().getAccountId();
		AuditContext.accounts(accountId, request.payoutAccountId());
		// 응답은 커밋 뒤 따로 읽는다(상세 조회와 같은 모양). 서비스가 돌려준 엔티티의 지연 로딩 연관관계를 트랜잭션 밖에서 읽지 않는다.
		return IdempotentResponses.created(result, URI.create("/accounts/" + accountId + "/time-deposit"),
				TimeDepositResponse.from(service.detail(accountId)));
	}

	/** GET /accounts/{id}/time-deposit: 정기예금 상세. 입출금 계좌면 404 TIME_DEPOSIT_NOT_FOUND */
	@GetMapping("/accounts/{id}/time-deposit")
	public TimeDepositResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		return TimeDepositResponse.from(service.detail(id));
	}

	/**
	 * POST /accounts/{id}/time-deposit/termination: 중도해지(만기일 이후면 만기 해지). 원리금은 연결계좌로 간다.
	 * 계좌 해지와 같이 상태를 바꾸므로 200, 돈이 나가므로 Idempotency-Key 필수.
	 */
	@PostMapping("/accounts/{id}/time-deposit/termination")
	public ResponseEntity<TimeDepositResponse> terminate(
			@RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
			@PathVariable Long id,
			@AuthenticationPrincipal Jwt jwt) {
		accessPolicy.checkAccess(Actor.from(jwt), id);
		IdempotentResult<TimeDepositService.Settled> result = service.terminate(id, new IdempotencyKey(idempotencyKey));
		AuditContext.accounts(id, result.value().deposit().getPayoutAccountId());
		return IdempotentResponses.ok(result, TimeDepositResponse.from(service.detail(id)));
	}

	/** POST /admin/time-deposits/maturities: 오늘까지 만기가 된 정기예금을 만기 해지한다(매일 00:40 자동 실행과 같다) */
	@PostMapping("/admin/time-deposits/maturities")
	public TimeDepositService.MaturityResult mature() {
		return service.matureDue();
	}

	/** GET /admin/time-deposits?status=&page=&size=: 정기예금 목록(최근 가입순) */
	@GetMapping("/admin/time-deposits")
	public Page search(
			@RequestParam(required = false) TimeDepositStatus status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		org.springframework.data.domain.Page<TimeDeposit> result = service.search(status, page, size);
		return new Page(result.getContent().stream().map(TimeDepositResponse::summary).toList(), result.getNumber(),
				result.getSize(), result.getTotalElements(), result.getTotalPages());
	}

}
