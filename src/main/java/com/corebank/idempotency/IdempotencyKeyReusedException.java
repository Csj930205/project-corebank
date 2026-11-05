package com.corebank.idempotency;

/**
 * 이미 쓴 키로 다른 내용의 요청이 왔을 때 던지는 예외. ApiExceptionHandler 가 422 응답으로 바꾼다.
 * 클라이언트가 새 요청에 키를 재사용한 버그이므로, 처음 결과를 돌려주면 안 된다.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException(IdempotencyKey key) {
		super("이미 다른 요청에 사용한 Idempotency-Key 입니다: " + key.value());
	}

}
