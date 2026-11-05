package com.corebank.account;

import java.time.LocalDateTime;

/**
 * 계좌 해지 응답.
 *
 * @param payoutAmount        해지하면서 고객에게 지급한 잔액. 잔액이 없었으면 0
 * @param payoutTransactionId 지급을 기록한 원장 거래 ID. 지급이 없었으면 null
 */
public record AccountClosureResponse(
		Long accountId,
		String accountNumber,
		AccountStatus status,
		long payoutAmount,
		Long payoutTransactionId,
		LocalDateTime closedAt) {

	static AccountClosureResponse from(AccountClosure closure) {
		Account account = closure.account();
		return new AccountClosureResponse(
				account.getId(),
				account.getAccountNumber(),
				account.getStatus(),
				closure.payoutAmount(),
				closure.payout() == null ? null : closure.payout().getId(),
				account.getClosedAt());
	}

}
