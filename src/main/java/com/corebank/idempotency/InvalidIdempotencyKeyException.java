package com.corebank.idempotency;

/**
 * 멱등성 키 형식이 잘못됐을 때 던지는 예외. ApiExceptionHandler 가 400 응답으로 바꾼다.
 */
public class InvalidIdempotencyKeyException extends RuntimeException {

	public InvalidIdempotencyKeyException() {
		super("Idempotency-Key 는 영문, 숫자, '-', '_' 로 된 1~100자여야 합니다.");
	}

}
