package com.corebank.ratelimit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 고정 구간 횟수 제한. "키마다 구간(예: 1시간) 안에 최대 N번" 을 DB 의 rate_limit 표로 센다.
 *
 * <p>구간은 시계 기준으로 자른다(1시간이면 14:00~15:00). 구간 경계 앞뒤로 몰아 쓰면 잠깐 동안 2N 번까지 될 수 있지만,
 * 계산이 단순하고 "이번 구간이 언제 끝나는지" 를 Retry-After 로 정확히 알려 줄 수 있다.
 *
 * <p>1을 더하는 UPDATE 가 행을 잠그므로 동시에 와도 하나씩 센다. 한도를 넘은 요청은 예외로 롤백되어 횟수에 들어가지 않는다.
 */
@Component
public class RateLimiter {

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public RateLimiter(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	/**
	 * 한 번 센다. 업무 트랜잭션과 따로 바로 커밋한다(업무가 실패해도 시도는 센다).
	 *
	 * @throws RateLimitExceededException 이번 구간에 이미 max 번을 썼을 때
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void hit(String key, int max, Duration window) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime windowStart = windowStart(now, window);
		Timestamp start = Timestamp.valueOf(windowStart);

		if (increment(key, start) == 0) {
			try {
				jdbcTemplate.update("INSERT INTO rate_limit (limit_key, window_start, hit_count) VALUES (?, ?, 1)",
						key, start);
			} catch (DuplicateKeyException e) {
				// 다른 요청이 같은 구간의 첫 행을 먼저 만들었다.
				increment(key, start);
			}
		}
		Integer count = jdbcTemplate.queryForObject(
				"SELECT hit_count FROM rate_limit WHERE limit_key = ? AND window_start = ?", Integer.class, key, start);
		if (count != null && count > max) {
			throw new RateLimitExceededException(Duration.between(now, windowStart.plus(window)));
		}
	}

	private int increment(String key, Timestamp start) {
		return jdbcTemplate.update(
				"UPDATE rate_limit SET hit_count = hit_count + 1 WHERE limit_key = ? AND window_start = ?", key, start);
	}

	/** now 가 속한 구간의 시작. 1970-01-01 00:00 부터 window 단위로 자른다(1시간이면 매시 정각). */
	static LocalDateTime windowStart(LocalDateTime now, Duration window) {
		long seconds = now.toEpochSecond(ZoneOffset.UTC);
		long size = window.toSeconds();
		return LocalDateTime.ofEpochSecond(seconds - Math.floorMod(seconds, size), 0, ZoneOffset.UTC);
	}

}
