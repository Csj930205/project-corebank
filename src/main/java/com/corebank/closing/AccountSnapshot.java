package com.corebank.closing;

import java.math.BigDecimal;

/**
 * 계좌 대사 단계가 읽는 계좌 한 줄. 계좌 잔액과 원장에서 계산한 값을 한 번에 담는다.
 *
 * @param balance              지금 account.balance
 * @param ledgerTotal          지금까지의 고객 예금 원장 합계
 * @param ledgerAtCutoff       마감 시점까지의 고객 예금 원장 합계
 * @param balanceAfterAtCutoff 마감 시점 이전 마지막 거래의 거래 후 잔액. 그때까지 거래가 없었으면 null
 */
public record AccountSnapshot(
		Long id,
		String accountNumber,
		BigDecimal balance,
		BigDecimal ledgerTotal,
		BigDecimal ledgerAtCutoff,
		BigDecimal balanceAfterAtCutoff) {
}
