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
	CLOSURE_PAYOUT,
	/** 역분개로 잔액이 준 거래. 입금이나 이체 입금(TRANSFER_IN)을 되돌린다. */
	REVERSAL_OUT,
	/** 역분개로 잔액이 는 거래. 출금이나 이체 출금(TRANSFER_OUT)을 되돌린다. */
	REVERSAL_IN;

	/** 이 거래가 고객 잔액을 늘리는지. 역분개는 이 방향을 반대로 한다. */
	public boolean increasesBalance() {
		return switch (this) {
			case DEPOSIT, TRANSFER_IN, REVERSAL_IN -> true;
			case WITHDRAWAL, TRANSFER_OUT, CLOSURE_PAYOUT, REVERSAL_OUT -> false;
		};
	}

	/**
	 * 정정(역분개)할 수 있는 거래인지.
	 * <ul>
	 *   <li>해지 지급은 안 된다. 해지 계좌에는 돈이 다시 들어갈 수 없다(ck_account_closed).</li>
	 *   <li>역분개는 안 된다. 역분개가 잘못됐다면 원래 거래를 다시 하면 된다. 되돌림의 되돌림을 허용하면 이력이 꼬인다.</li>
	 * </ul>
	 */
	public boolean isCorrectable() {
		return switch (this) {
			case DEPOSIT, WITHDRAWAL, TRANSFER_OUT, TRANSFER_IN -> true;
			case CLOSURE_PAYOUT, REVERSAL_OUT, REVERSAL_IN -> false;
		};
	}
}
