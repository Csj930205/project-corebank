package com.corebank.account;

import com.corebank.ledger.LedgerTransaction;

import java.time.LocalDateTime;

/**
 * 입금 결과.
 *
 * @param transactionId 원장 거래 ID
 * @param balanceAfter  입금 후 잔액
 */
public record DepositResponse(
		Long transactionId,
		Long accountId,
		long amount,
		long balanceAfter,
		LocalDateTime createdAt) {

	static DepositResponse from(LedgerTransaction transaction) {
		return new DepositResponse(
				transaction.getId(),
				transaction.getAccountId(),
				transaction.getAmount(),
				transaction.getBalanceAfter(),
				transaction.getCreatedAt());
	}

}
