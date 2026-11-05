package com.corebank.account;

/**
 * 잔액보다 큰 금액을 출금하려 할 때 던지는 업무 예외. ApiExceptionHandler 가 422 응답으로 바꾼다.
 */
public class InsufficientBalanceException extends RuntimeException {

	public InsufficientBalanceException(Long id) {
		super("잔액이 부족합니다. id=" + id);
	}

}
