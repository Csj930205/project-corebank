package com.corebank.interest;

/** 이자를 지급한 계기. DB 의 ck_interest_payment_trigger 와 값이 일치해야 한다. */
public enum InterestTrigger {
	/** 월 결산 */
	SETTLEMENT,
	/** 계좌 해지(그때까지 쌓인 이자를 남은 잔액과 함께 돌려준다) */
	CLOSURE,
	/** 정기예금 만기. 원금 × 약정이율 × 가입 일수 */
	MATURITY,
	/** 정기예금 중도해지. 원금 × 중도해지이율 × 경과 일수 */
	EARLY_TERM
}
