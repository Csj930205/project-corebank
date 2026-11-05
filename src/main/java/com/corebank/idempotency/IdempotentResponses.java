package com.corebank.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/**
 * 멱등 처리 결과를 HTTP 응답으로 바꾸는 도우미.
 *
 * <p>다시 보낸 요청에도 처음과 같은 상태 코드(201)와 본문을 돌려준다. 클라이언트 입장에서는 첫 응답을 잃어버렸다가
 * 다시 받은 것과 같아야 하기 때문이다. 다시 보낸 요청이었다는 사실은 Idempotent-Replayed 헤더로만 알린다.
 */
public final class IdempotentResponses {

	public static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private IdempotentResponses() {
	}

	public static <T> ResponseEntity<T> created(IdempotentResult<?> result, T body) {
		return builder(result, ResponseEntity.status(HttpStatus.CREATED)).body(body);
	}

	/** 새 자원을 만들지 않고 기존 자원의 상태를 바꾼 요청(계좌 해지)용. 다시 보낸 요청도 같은 200 이다. */
	public static <T> ResponseEntity<T> ok(IdempotentResult<?> result, T body) {
		return builder(result, ResponseEntity.ok()).body(body);
	}

	public static <T> ResponseEntity<T> created(IdempotentResult<?> result, URI location, T body) {
		return builder(result, ResponseEntity.created(location)).body(body);
	}

	private static ResponseEntity.BodyBuilder builder(IdempotentResult<?> result, ResponseEntity.BodyBuilder builder) {
		return result.replayed() ? builder.header(REPLAYED_HEADER, "true") : builder;
	}

}
