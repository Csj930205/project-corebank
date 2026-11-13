package com.corebank.timedeposit;

/** 정기예금 계약이 없는 계좌(입출금 계좌이거나 없는 계좌). 404 */
public class TimeDepositNotFoundException extends RuntimeException {

	public TimeDepositNotFoundException(Long accountId) {
		super("정기예금 계좌가 아닙니다. id=" + accountId);
	}

}
