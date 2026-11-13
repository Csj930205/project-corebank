package com.corebank.account;

/** 가입 중인 정기예금의 연결계좌를 해지하려 함. 만기·중도해지 때 원리금을 받을 곳이 없어진다. 409 */
public class LinkedTimeDepositExistsException extends RuntimeException {

	public LinkedTimeDepositExistsException(Long accountId) {
		super("가입 중인 정기예금의 연결계좌는 해지할 수 없습니다. 정기예금을 먼저 해지하세요. id=" + accountId);
	}

}
