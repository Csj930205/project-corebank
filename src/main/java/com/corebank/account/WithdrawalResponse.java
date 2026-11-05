package com.corebank.account;

import com.corebank.ledger.LedgerTransaction;

import java.time.LocalDateTime;

/**
 * 출금 결과.
 *
 * @param transactionId 원장 거래 ID
 * @param balanceAfter  출금 후 잔액
 */
public record WithdrawalResponse(
		Long transactionId,
		Long accountId,
		long amount,
		long balanceAfter,
		LocalDateTime createdAt) {

	static WithdrawalResponse from(LedgerTransaction transaction) {
		return new WithdrawalResponse(
				transaction.getId(),
				transaction.getAccountId(),
				transaction.getAmount(),
				transaction.getBalanceAfter(),
				transaction.getCreatedAt());
	}

}
