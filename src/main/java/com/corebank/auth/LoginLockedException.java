package com.corebank.auth;

import java.time.Duration;

/**
 * 로그인 실패가 쌓여 잠긴 아이디. 비밀번호를 확인하지 않고 거부한다(맞는 비밀번호여도 잠금이 풀릴 때까지 거부).
 *
 * @param retryAfter 잠금이 풀릴 때까지 남은 시간. 응답의 Retry-After 헤더가 된다.
 */
public class LoginLockedException extends RuntimeException {

	private final Duration retryAfter;

	public LoginLockedException(Duration retryAfter) {
		super("로그인 실패가 너무 많아 잠시 잠겼습니다.");
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

}
