package com.corebank.account;

import com.corebank.auth.Actor;
import com.corebank.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 수취인 조회 횟수 제한. 고객마다 구간(기본 1시간) 안에 최대 N번(기본 30).
 *
 * <p>수취인 조회는 남의 계좌번호로 가린 이름(홍*동)을 알려 준다. 횟수를 묶지 않으면 계좌번호를 바꿔 가며 조회해 이름을 모을 수 있다.
 * 관리자(창구 직원)는 업무상 조회가 많아 묶지 않고, 대신 모든 조회가 감사 로그에 남는다.
 */
@Component
public class RecipientLookupLimit {

	private final RateLimiter rateLimiter;
	private final int max;
	private final Duration window;

	public RecipientLookupLimit(RateLimiter rateLimiter,
			@Value("${corebank.rate-limit.recipient-lookup.max:30}") int max,
			@Value("${corebank.rate-limit.recipient-lookup.window:1h}") Duration window) {
		this.rateLimiter = rateLimiter;
		this.max = max;
		this.window = window;
	}

	/** @throws com.corebank.ratelimit.RateLimitExceededException 이번 구간에 이미 max 번 조회한 고객 */
	public void check(Actor actor) {
		if (actor.isCustomer()) {
			rateLimiter.hit("recipient-lookup:customer:" + actor.customerId(), max, window);
		}
	}

}
