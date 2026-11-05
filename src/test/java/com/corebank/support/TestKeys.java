package com.corebank.support;

import com.corebank.idempotency.IdempotencyKey;

import java.util.UUID;

/**
 * 테스트용 멱등성 키. 매번 새 키를 만들어 "서로 다른 요청"을 표현한다.
 */
public final class TestKeys {

	private TestKeys() {
	}

	public static IdempotencyKey newKey() {
		return new IdempotencyKey(UUID.randomUUID().toString());
	}

}
