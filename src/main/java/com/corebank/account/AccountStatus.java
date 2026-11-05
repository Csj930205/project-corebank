package com.corebank.account;

/**
 * 계좌 상태. DB의 ck_account_status 제약과 값이 일치해야 한다.
 */
public enum AccountStatus {
	/** 정상 계좌 */
	ACTIVE,
	/** 해지된 계좌 */
	CLOSED
}
