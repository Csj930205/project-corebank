package com.corebank.transfer;

/**
 * 보내는 계좌와 받는 계좌가 같을 때 던지는 예외. ApiExceptionHandler 가 400 응답으로 바꾼다.
 */
public class SameAccountTransferException extends RuntimeException {

	public SameAccountTransferException(Long id) {
		super("같은 계좌로는 이체할 수 없습니다. id=" + id);
	}

}
