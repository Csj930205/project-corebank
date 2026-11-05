package com.corebank.transfer;

import com.corebank.account.AccountAccessPolicy;
import com.corebank.account.AccountService;
import com.corebank.auth.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotentResponses;
import com.corebank.idempotency.IdempotentResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 이체 REST API. 이체는 두 계좌에 걸친 거래라 /accounts/{id} 아래가 아니라 별도 자원(/transfers)으로 둔다.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

	private final TransferService transferService;
	private final AccountAccessPolicy accessPolicy;
	private final AccountService accountService;

	public TransferController(TransferService transferService, AccountAccessPolicy accessPolicy,
			AccountService accountService) {
		this.transferService = transferService;
		this.accessPolicy = accessPolicy;
		this.accountService = accountService;
	}

	/**
	 * POST /transfers: 이체. 201 과 함께 Location 헤더로 이체 기록의 주소를 알려 준다.
	 * Idempotency-Key 헤더가 필수이고, 같은 키로 다시 보내면 처음 이체 결과를 돌려준다.
	 * 고객은 본인 계좌에서만 보낼 수 있다. 받는 계좌는 toAccountId 또는 toAccountNumber 로 정한다.
	 */
	@PostMapping
	public ResponseEntity<TransferResponse> transfer(
			@RequestHeader(IdempotencyKey.HEADER) String idempotencyKey,
			@Valid @RequestBody TransferRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		// 보내는 계좌만 본인 것이어야 한다. 받는 계좌는 다른 사람 계좌가 보통이다(없으면 서비스가 404).
		Actor actor = Actor.from(jwt);
		accessPolicy.checkAccess(actor, request.fromAccountId());
		// 계좌번호로 받았으면 id 로 바꾼다. 계좌번호는 바뀌지 않으므로 잠금 전에 바꿔도 안전하다.
		Long toAccountId = request.toAccountId() != null
				? request.toAccountId()
				: accountService.idOf(request.toAccountNumber());
		IdempotentResult<TransferResult> result = transferService.transfer(
				request.fromAccountId(), toAccountId, request.amount(), new IdempotencyKey(idempotencyKey));
		Transfer transfer = result.value().transfer();
		return IdempotentResponses.created(result, URI.create("/transfers/" + transfer.getId()),
				TransferResponse.from(result.value(), actor.isAdmin()));
	}

}
