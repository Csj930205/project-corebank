package com.corebank.idempotency;

/**
 * 멱등성 키를 쓰는 작업 종류. DB 의 ck_idempotency_operation 제약과 값이 일치해야 한다.
 */
public enum IdempotentOperation {
	/** resource_id = ledger_transaction.id */
	DEPOSIT,
	/** resource_id = ledger_transaction.id */
	WITHDRAWAL,
	/** resource_id = transfer.id */
	TRANSFER,
	/** resource_id = account.id (해지한 계좌) */
	CLOSURE
}
