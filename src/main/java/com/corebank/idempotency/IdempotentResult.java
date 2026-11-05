package com.corebank.idempotency;

/**
 * 멱등 처리 결과.
 *
 * @param value    처리 결과. 다시 보낸 요청이면 처음 처리했을 때의 결과다.
 * @param replayed 다시 보낸 요청이라 새로 처리하지 않고 저장된 결과를 돌려줬으면 true
 */
public record IdempotentResult<T>(T value, boolean replayed) {

	public static <T> IdempotentResult<T> created(T value) {
		return new IdempotentResult<>(value, false);
	}

	public static <T> IdempotentResult<T> replayed(T value) {
		return new IdempotentResult<>(value, true);
	}

}
