package com.corebank.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 횟수 제한 기록 정리. 매시 30분에 하루 지난 기록을 지운다.
 *
 * <ul>
 *   <li>rate_limit: 끝난 구간은 다시 쓰이지 않는다.</li>
 *   <li>login_attempt: 하루 동안 시도가 없고 잠겨 있지 않은 아이디. 실패 횟수도 함께 지워져 처음부터 센다.
 *       지우지 않으면 아무 아이디나 넣어 보는 시도마다 행이 쌓인다.</li>
 * </ul>
 */
@Component
public class RateLimitCleanup {

	private static final Logger log = LoggerFactory.getLogger(RateLimitCleanup.class);

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public RateLimitCleanup(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	@Scheduled(cron = "${corebank.rate-limit.cleanup-cron:0 30 * * * *}")
	public void cleanUp() {
		LocalDateTime now = LocalDateTime.now(clock);
		Timestamp dayAgo = Timestamp.valueOf(now.minusDays(1));
		int windows = jdbcTemplate.update("DELETE FROM rate_limit WHERE window_start < ?", dayAgo);
		int attempts = jdbcTemplate.update("""
				DELETE FROM login_attempt
				WHERE updated_at < ? AND (locked_until IS NULL OR locked_until < ?)
				""", dayAgo, Timestamp.valueOf(now));
		if (windows + attempts > 0) {
			log.info("횟수 제한 기록 정리: 구간 {}건, 로그인 시도 {}건", windows, attempts);
		}
	}

}
