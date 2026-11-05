package com.corebank.transfer;

import java.time.LocalDateTime;

/**
 * 이체 결과.
 *
 * @param transferId       이체 번호
 * @param fromBalanceAfter 이체 후 보내는 계좌 잔액
 * @param toBalanceAfter   이체 후 받는 계좌 잔액. 관리자에게만 준다. 고객에게는 null 이다(받는 계좌는 보통 남의
 *                         계좌라, 주면 이체 한 번으로 남의 잔액을 알아낼 수 있다).
 */
public record TransferResponse(
		Long transferId,
		Long fromAccountId,
		Long toAccountId,
		long amount,
		long fromBalanceAfter,
		Long toBalanceAfter,
		LocalDateTime createdAt) {

	/** @param showRecipientBalance 받는 계좌 잔액을 보여 줄지(관리자만) */
	static TransferResponse from(TransferResult result, boolean showRecipientBalance) {
		Transfer transfer = result.transfer();
		return new TransferResponse(
				transfer.getId(),
				transfer.getFromAccountId(),
				transfer.getToAccountId(),
				transfer.getAmount(),
				result.fromBalanceAfter(),
				showRecipientBalance ? result.toBalanceAfter() : null,
				transfer.getCreatedAt());
	}

}
