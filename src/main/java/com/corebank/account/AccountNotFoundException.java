package com.corebank.account;

/**
 * 요청한 계좌가 없을 때 던지는 업무 예외. ApiExceptionHandler 가 404 응답으로 바꾼다.
 */
public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(Long id) {
		super("계좌를 찾을 수 없습니다. id=" + id);
	}

	private AccountNotFoundException(String message) {
		super(message);
	}

	/** 계좌번호로 찾다가 없을 때. 메시지에 내부 id 를 넣지 않는다. */
	public static AccountNotFoundException byNumber(String accountNumber) {
		return new AccountNotFoundException("계좌를 찾을 수 없습니다. accountNumber=" + accountNumber);
	}

}
