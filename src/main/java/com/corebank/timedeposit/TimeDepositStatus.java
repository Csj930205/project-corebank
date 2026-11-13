package com.corebank.timedeposit;

/** 정기예금 상태. DB 의 ck_time_deposit_status 와 값이 일치해야 한다. ACTIVE 에서 둘 중 하나로 한 번만 바뀐다. */
public enum TimeDepositStatus {
	/** 가입 중 */
	ACTIVE,
	/** 만기 해지. 약정이율로 이자를 주고 원리금을 연결계좌로 보냈다 */
	MATURED,
	/** 중도 해지. 중도해지이율로 이자를 주고 원리금을 연결계좌로 보냈다 */
	TERMINATED
}
