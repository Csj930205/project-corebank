package com.corebank.account;

/**
 * 해지된 계좌로 거래를 시도할 때 던지는 업무 예외. ApiExceptionHandler 가 409 응답으로 바꾼다.
 */
public class AccountClosedException extends RuntimeException {

	public AccountClosedException(Long id) {
		super("해지된 계좌입니다. id=" + id);
	}

	private AccountClosedException(String message) {
		super(message);
	}

	/** 계좌번호로 찾은 계좌가 해지됐을 때. 메시지에 내부 id 를 넣지 않는다. */
	public static AccountClosedException byNumber(String accountNumber) {
		return new AccountClosedException("해지된 계좌입니다. accountNumber=" + accountNumber);
	}

}
