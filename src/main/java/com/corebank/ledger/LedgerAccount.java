package com.corebank.ledger;

/**
 * 원장의 계정과목. 고객 계좌(account 테이블)와는 다른 개념이다.
 */
public enum LedgerAccount {
	/** 은행이 보유한 현금 (자산) */
	CASH,
	/** 은행이 고객에게 돌려줘야 할 예금 (부채) */
	CUSTOMER_DEPOSIT,
	/**
	 * 이체 가계정 (은행 내부). 이체의 출금 쪽 거래에서 대변, 입금 쪽 거래에서 차변으로 같은 금액이 들어가므로
	 * 이체가 끝나면 합계가 항상 0이다. 0이 아니면 반쪽만 기록된 이체가 있다는 뜻이다.
	 */
	TRANSFER_CLEARING
}
