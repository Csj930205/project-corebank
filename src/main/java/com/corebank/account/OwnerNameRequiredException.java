package com.corebank.account;

/** 관리자가 예금주 이름 없이 계좌를 열려 할 때. 다른 입력 오류와 같은 400 INVALID_REQUEST 로 응답한다. */
public class OwnerNameRequiredException extends RuntimeException {

	public OwnerNameRequiredException() {
		super("예금주 이름(ownerName)이 필요합니다.");
	}

}
