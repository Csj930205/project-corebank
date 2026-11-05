package com.corebank.closing;

/**
 * 대사에서 찾는 불일치 종류. DB 의 ck_closing_mismatch_type 제약과 값이 일치해야 한다.
 */
public enum MismatchType {
	/** 계좌의 현재 잔액(account.balance)이 그 계좌 고객 예금 원장 합계와 다르다. 잔액 컬럼이 원장 없이 바뀐 경우다. */
	BALANCE_LEDGER_MISMATCH,
	/** 마감 시점 마지막 거래의 "거래 후 잔액"이 마감 시점까지의 원장 합계와 다르다. 거래 기록이 원장과 어긋난 경우다. */
	SNAPSHOT_MISMATCH,
	/** 영업일 거래 한 건의 차변 합과 대변 합이 다르다(복식부기 위반). */
	UNBALANCED_TRANSACTION,
	/** 영업일 이체 한 건의 원장 거래가 2건(출금 쪽, 입금 쪽)이 아니다. 반쪽만 기록된 이체다. */
	INCOMPLETE_TRANSFER,
	/** 마감 시점 이체 가계정 잔액이 0이 아니다. 모든 이체가 완결됐다면 0이어야 한다. */
	CLEARING_NOT_ZERO
}
