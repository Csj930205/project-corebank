package com.corebank.account;

/**
 * 잔액이 long 범위를 넘을 때 던지는 업무 예외. ApiExceptionHandler 가 422 응답으로 바꾼다.
 */
public class BalanceLimitExceededException extends RuntimeException {

	public BalanceLimitExceededException(Long id) {
		super("잔액 한도를 초과했습니다. id=" + id);
	}

}
