package com.corebank.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 로그인 시도 횟수 제한. 아이디마다 연속 실패가 maxFailures 번이 되면 lockDuration 동안 잠근다. 성공하면 처음부터 센다.
 *
 * <p><b>비밀번호를 확인하기 전에 센다.</b> 확인 뒤에 세면, 같은 아이디로 동시에 요청 100개를 보낼 때 모두 "아직 실패 0번"
 * 을 보고 통과해 100번을 시도할 수 있다. 그래서 시도마다 먼저 행을 잠그고(SELECT FOR UPDATE) 1을 더해 커밋한 뒤 비밀번호를
 * 확인하고, 성공하면 기록을 지운다. 동시에 몇 개가 오든 비밀번호 확인은 아이디마다 최대 maxFailures 번이다.
 * 행 잠금은 1을 더하는 짧은 순간에만 잡고, 시간이 걸리는 BCrypt 확인 동안에는 잡지 않는다.
 *
 * <p>각 메서드는 자기 트랜잭션(REQUIRES_NEW)에서 바로 커밋한다. 로그인 실패는 예외로 끝나 바깥 트랜잭션이 롤백되는데,
 * 실패 횟수까지 함께 롤백되면 안 되기 때문이다.
 */
@Component
public class LoginThrottle {

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;
	private final int maxFailures;
	private final Duration lockDuration;

	public LoginThrottle(JdbcTemplate jdbcTemplate, Clock clock,
			@Value("${corebank.login.max-failures:5}") int maxFailures,
			@Value("${corebank.login.lock-duration:15m}") Duration lockDuration) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
		this.maxFailures = maxFailures;
		this.lockDuration = lockDuration;
	}

	private record Attempt(int failureCount, LocalDateTime lockedUntil) {
	}

	/**
	 * 지금 잠긴 아이디 한 줄. 없는 아이디도 잠기므로(계정 존재를 숨기려고) 목록에는 가입하지 않은 아이디도 나온다.
	 *
	 * @param failureCount 잠기기까지의 연속 실패 수
	 * @param lockedUntil  이 시각에 저절로 풀린다
	 */
	public record LockedLogin(String username, int failureCount, LocalDateTime lockedUntil) {
	}

	/** 지금 잠긴 아이디. 곧 풀리는 순서. 많아도 앞의 limit 개만 돌려준다(관리자 화면용). */
	@Transactional(readOnly = true)
	public List<LockedLogin> locked(int limit) {
		return jdbcTemplate.query("""
						SELECT username, failure_count, locked_until FROM login_attempt
						WHERE locked_until > ?
						ORDER BY locked_until, username
						FETCH FIRST ? ROWS ONLY
						""",
				(rs, n) -> new LockedLogin(rs.getString("username"), rs.getInt("failure_count"),
						rs.getTimestamp("locked_until").toLocalDateTime()),
				Timestamp.valueOf(now()), limit);
	}

	/**
	 * 시도 한 번을 실패로 미리 센다. 성공하면 succeeded 가 지운다.
	 *
	 * @throws LoginLockedException 이미 잠긴 아이디
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void acquire(String username) {
		LocalDateTime now = now();
		Attempt attempt = lockRow(username, now);
		if (attempt.lockedUntil() != null && attempt.lockedUntil().isAfter(now)) {
			throw new LoginLockedException(Duration.between(now, attempt.lockedUntil()));
		}
		// 잠금이 풀린 뒤 첫 시도면 처음부터 센다.
		int failures = (attempt.lockedUntil() != null ? 0 : attempt.failureCount()) + 1;
		LocalDateTime lockedUntil = failures >= maxFailures ? now.plus(lockDuration) : null;
		jdbcTemplate.update("""
				UPDATE login_attempt SET failure_count = ?, locked_until = ?, updated_at = ? WHERE username = ?
				""", failures, lockedUntil == null ? null : Timestamp.valueOf(lockedUntil), Timestamp.valueOf(now),
				username);
	}

	/** 로그인 성공. 미리 센 실패와 잠금을 지운다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void succeeded(String username) {
		jdbcTemplate.update("DELETE FROM login_attempt WHERE username = ?", username);
	}

	/**
	 * 관리자의 잠금 해제(창구에서 본인 확인을 한 뒤).
	 *
	 * @return 지운 기록이 있었으면 true
	 */
	@Transactional
	public boolean unlock(String username) {
		return jdbcTemplate.update("DELETE FROM login_attempt WHERE username = ?", username) > 0;
	}

	/** 아이디의 행을 잠그고 읽는다. 처음 보는 아이디면 만든다. 동시에 처음 만들면 한쪽은 중복으로 실패하고 다시 읽는다. */
	private Attempt lockRow(String username, LocalDateTime now) {
		List<Attempt> rows = selectForUpdate(username);
		if (rows.isEmpty()) {
			try {
				jdbcTemplate.update("""
						INSERT INTO login_attempt (username, failure_count, locked_until, updated_at) VALUES (?, 0, NULL, ?)
						""", username, Timestamp.valueOf(now));
			} catch (DuplicateKeyException e) {
				// 다른 요청이 먼저 만들었다. 아래에서 그 행을 잠그고 읽는다.
			}
			rows = selectForUpdate(username);
		}
		return rows.getFirst();
	}

	private List<Attempt> selectForUpdate(String username) {
		return jdbcTemplate.query(
				"SELECT failure_count, locked_until FROM login_attempt WHERE username = ? FOR UPDATE",
				(rs, n) -> {
					Timestamp lockedUntil = rs.getTimestamp("locked_until");
					return new Attempt(rs.getInt("failure_count"),
							lockedUntil == null ? null : lockedUntil.toLocalDateTime());
				}, username);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
	}

}
