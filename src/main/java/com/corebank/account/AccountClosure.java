package com.corebank.account;

import com.corebank.ledger.LedgerTransaction;

/**
 * 계좌 해지 결과.
 *
 * @param account 해지된 계좌
 * @param payout  남은 잔액을 지급한 원장 거래. 잔액 0으로 해지했으면 null
 */
public record AccountClosure(Account account, LedgerTransaction payout) {

	/** 고객에게 지급한 금액. 지급이 없었으면 0 */
	public long payoutAmount() {
		return payout == null ? 0 : payout.getAmount();
	}

}
