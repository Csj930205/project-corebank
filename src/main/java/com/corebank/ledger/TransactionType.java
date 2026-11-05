package com.corebank.ledger;

/**
 * 거래 종류. DB의 ck_ledger_transaction_type 제약과 값이 일치해야 한다.
 */
public enum TransactionType {
	DEPOSIT,
	WITHDRAWAL,
	/** 이체로 나간 돈. 보내는 계좌의 거래다. */
	TRANSFER_OUT,
	/** 이체로 들어온 돈. 받는 계좌의 거래다. */
	TRANSFER_IN,
	/** 계좌 해지 때 남은 잔액을 고객에게 지급한 돈. 계좌당 최대 한 건이다. */
	CLOSURE_PAYOUT
}
