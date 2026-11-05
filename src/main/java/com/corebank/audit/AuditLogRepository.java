package com.corebank.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 감사 로그 테이블 접근. 쓰기는 한 줄 INSERT 뿐이고(수정·삭제는 DB 트리거가 막는다), 조회는 조건이 그때그때 달라 SQL 을 직접 쓴다.
 */
@Repository
public class AuditLogRepository {

	private final JdbcTemplate jdbcTemplate;

	public AuditLogRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 감사 기록 한 줄을 쓴다. 호출하는 쪽(필터)은 업무 트랜잭션이 끝난 뒤라 트랜잭션 밖이고, 그래서 바로 커밋된다.
	 * 업무가 실패해 롤백되어도 감사 기록은 남는다.
	 */
	public void insert(AuditLogEntry e) {
		jdbcTemplate.update("""
						INSERT INTO audit_log (id, occurred_at, request_id, actor_type, actor_username, customer_id, action,
						    http_method, path, status_code, outcome, error_code, target_account_id, counterpart_account_id,
						    client_ip, user_agent, duration_ms)
						VALUES (audit_log_seq.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""",
				Timestamp.valueOf(e.occurredAt()), e.requestId(), e.actorType().name(), truncate(e.actorUsername(), 50),
				e.customerId(), e.action().name(), e.httpMethod(), truncate(e.path(), 500), e.statusCode(),
				e.outcome().name(), truncate(e.errorCode(), 50), e.targetAccountId(), e.counterpartAccountId(),
				truncate(e.clientIp(), 45), truncate(e.userAgent(), 300), e.durationMs());
	}

	/**
	 * 조회 조건. 값이 null 인 조건은 쓰지 않는다.
	 *
	 * @param actor     아이디(정확히 일치)
	 * @param accountId 대상 계좌 또는 이체 상대 계좌
	 * @param from, to  기간(날짜, 양 끝 포함)
	 */
	public record Criteria(String actor, AuditAction action, AuditLogEntry.Outcome outcome, Long accountId,
			LocalDate from, LocalDate to) {
	}

	public record Page(List<AuditLogEntry> content, long totalElements) {
	}

	/** 최신순(id 역순)으로 page 번째 쪽을 돌려준다. */
	public Page search(Criteria c, int page, int size) {
		StringBuilder where = new StringBuilder(" WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (c.actor() != null) {
			where.append(" AND actor_username = ?");
			args.add(c.actor());
		}
		if (c.action() != null) {
			where.append(" AND action = ?");
			args.add(c.action().name());
		}
		if (c.outcome() != null) {
			where.append(" AND outcome = ?");
			args.add(c.outcome().name());
		}
		if (c.accountId() != null) {
			where.append(" AND (target_account_id = ? OR counterpart_account_id = ?)");
			args.add(c.accountId());
			args.add(c.accountId());
		}
		if (c.from() != null) {
			where.append(" AND occurred_at >= ?");
			args.add(Timestamp.valueOf(c.from().atStartOfDay()));
		}
		if (c.to() != null) {
			// 끝 날짜를 포함하도록 다음 날 0시 "전" 까지
			where.append(" AND occurred_at < ?");
			args.add(Timestamp.valueOf(c.to().plusDays(1).atStartOfDay()));
		}

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Long.class, args.toArray());

		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add((long) page * size);
		pageArgs.add(size);
		List<AuditLogEntry> content = jdbcTemplate.query(
				"SELECT * FROM audit_log" + where + " ORDER BY id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
				ROW_MAPPER, pageArgs.toArray());
		return new Page(content, total == null ? 0 : total);
	}

	private static final RowMapper<AuditLogEntry> ROW_MAPPER = (rs, n) -> new AuditLogEntry(
			rs.getLong("id"),
			rs.getTimestamp("occurred_at").toLocalDateTime(),
			rs.getString("request_id"),
			AuditLogEntry.ActorType.valueOf(rs.getString("actor_type")),
			rs.getString("actor_username"),
			rs.getObject("customer_id", Long.class),
			AuditAction.valueOf(rs.getString("action")),
			rs.getString("http_method"),
			rs.getString("path"),
			rs.getInt("status_code"),
			AuditLogEntry.Outcome.valueOf(rs.getString("outcome")),
			rs.getString("error_code"),
			rs.getObject("target_account_id", Long.class),
			rs.getObject("counterpart_account_id", Long.class),
			rs.getString("client_ip"),
			rs.getString("user_agent"),
			rs.getLong("duration_ms"));

	/** 컬럼보다 긴 값(아주 긴 User-Agent 등) 때문에 기록 자체가 실패하지 않게 자른다. */
	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
