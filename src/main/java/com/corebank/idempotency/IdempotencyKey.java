package com.corebank.idempotency;

import java.util.regex.Pattern;

/**
 * 클라이언트가 Idempotency-Key 헤더로 보내는 키. 만들 때 형식을 검사하므로, 이 타입을 받는 코드는 다시 검사할 필요가 없다.
 *
 * <p>클라이언트는 "같은 요청을 다시 보낼 때"만 같은 키를 쓰고, 새 요청에는 새 키(보통 UUID)를 만든다.
 */
public record IdempotencyKey(String value) {

	public static final String HEADER = "Idempotency-Key";

	// UUID(36자)를 넉넉히 담고, 로그나 SQL 에 섞여도 문제없는 문자만 허용한다.
	private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,100}");

	public IdempotencyKey {
		if (value == null || !FORMAT.matcher(value).matches()) {
			throw new InvalidIdempotencyKeyException();
		}
	}

}
