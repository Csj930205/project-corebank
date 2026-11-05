package com.corebank.ratelimit;

import java.time.Duration;

/**
 * 구간 안에 허용 횟수를 다 썼다.
 *
 * @param retryAfter 이번 구간이 끝날 때까지 남은 시간. 응답의 Retry-After 헤더가 된다.
 */
public class RateLimitExceededException extends RuntimeException {

	private final Duration retryAfter;

	public RateLimitExceededException(Duration retryAfter) {
		super("요청이 너무 많습니다.");
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

}
