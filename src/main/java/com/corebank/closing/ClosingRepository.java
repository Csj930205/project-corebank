package com.corebank.closing;

import com.corebank.ledger.TransactionType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 마감 결과 테이블 접근. 대사는 여러 테이블을 묶어 합계를 내는 일이라 JPA 대신 SQL 을 직접 쓴다.
 *
 * <p>금액 합계는 여러 계좌를 더하므로 long 범위를 넘을 수 있다. 그래서 BigDecimal 로 받는다.
 *
 * <p>"고객 예금 원장 합계" 는 계좌 입장의 잔액이므로 대변(CREDIT, 들어온 돈)을 +, 차변(DEBIT)을 - 로 더한다.
 */
@Repository
public class ClosingRepository {

	/** 고객 예금 분개를 계좌 잔액 방향(+ 들어옴, - 나감)으로 바꾸는 식 */
	static final String SIGNED_AMOUNT = "CASE e.entry_side WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";

	private final JdbcTemplate jdbcTemplate;

	public ClosingRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// ───────────────────────── 실행 상태 ─────────────────────────

	/**
	 * RUNNING 상태로 실행 한 건을 만든다. 같은 영업일이 이미 RUNNING 이면 유니크 인덱스(ux_daily_closing_running)가 막는다.
	 * "조회해 보고 없으면 넣기" 는 두 요청이 동시에 조회하면 둘 다 통과하므로, 확인은 DB 에 맡긴다.
	 */
	public long createRunning(LocalDate businessDate, ClosingTrigger trigger, String requestedBy, LocalDateTime now) {
		long id = jdbcTemplate.queryForObject("SELECT daily_closing_seq.NEXTVAL FROM dual", Long.class);
		try {
			jdbcTemplate.update("""
					INSERT INTO daily_closing (id, business_date, status, trigger_type, requested_by, started_at)
					VALUES (?, ?, 'RUNNING', ?, ?, ?)
					""", id, Date.valueOf(businessDate), trigger.name(), requestedBy, Timestamp.valueOf(now));
		} catch (DuplicateKeyException e) {
			throw new ClosingAlreadyRunningException(businessDate);
		}
		return id;
	}

	/**
	 * 오래전에 시작했는데 아직 RUNNING 인 실행을 FAILED 로 바꾼다. 실행 중 서버가 꺼지면 RUNNING 이 영원히 남아
	 * 그 영업일을 다시 마감할 수 없게 되므로, 새 실행을 시작하기 전에 정리한다.
	 */
	public int failStale(LocalDateTime startedBefore, LocalDateTime now) {
		return jdbcTemplate.update("""
				UPDATE daily_closing
				SET status = 'FAILED', finished_at = ?, error_message = '실행 중 서버가 중단되어 끝나지 않았습니다.'
				WHERE status = 'RUNNING' AND started_at < ?
				""", Timestamp.valueOf(now), Timestamp.valueOf(startedBefore));
	}

	public void attachJobExecution(long closingId, long jobExecutionId) {
		jdbcTemplate.update("UPDATE daily_closing SET job_execution_id = ? WHERE id = ?", jobExecutionId, closingId);
	}

	/**
	 * 대사를 끝내고 집계 값과 결과 상태를 남긴다. 불일치가 0건이면 BALANCED, 아니면 UNBALANCED.
	 * RUNNING 인 행만 바꾸므로, 그사이 FAILED 로 정리된 실행을 덮어쓰지 않는다.
	 */
	public void finish(long closingId, LocalDateTime cutoff, LocalDateTime now) {
		jdbcTemplate.update("""
				UPDATE daily_closing d
				SET account_count = (SELECT COUNT(*) FROM account WHERE created_at < ?),
				    deposit_liability = (
				        SELECT COALESCE(SUM(%s), 0)
				        FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
				        WHERE e.ledger_account = 'CUSTOMER_DEPOSIT' AND t.created_at < ?),
				    mismatch_count = (SELECT COUNT(*) FROM closing_mismatch m WHERE m.closing_id = d.id),
				    status = CASE WHEN EXISTS (SELECT 1 FROM closing_mismatch m WHERE m.closing_id = d.id)
				                  THEN 'UNBALANCED' ELSE 'BALANCED' END,
				    finished_at = ?
				WHERE id = ? AND status = 'RUNNING'
				""".formatted(SIGNED_AMOUNT),
				Timestamp.valueOf(cutoff), Timestamp.valueOf(cutoff), Timestamp.valueOf(now), closingId);
	}

	/** 배치가 실패했을 때. 메시지는 컬럼 길이(1000자)에 맞춰 자른다. */
	public void markFailed(long closingId, String message, LocalDateTime now) {
		String trimmed = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
		jdbcTemplate.update("""
				UPDATE daily_closing SET status = 'FAILED', finished_at = ?, error_message = ?
				WHERE id = ? AND status = 'RUNNING'
				""", Timestamp.valueOf(now), trimmed, closingId);
	}

	// ───────────────────────── 대사 ─────────────────────────

	/** 일계표: 영업일 [from, to) 거래를 종류별로 센다. */
	public void insertDailyTotals(long closingId, LocalDateTime from, LocalDateTime to) {
		jdbcTemplate.update("""
				INSERT INTO daily_closing_total (closing_id, transaction_type, tx_count, amount)
				SELECT ?, transaction_type, COUNT(*), SUM(amount)
				FROM ledger_transaction
				WHERE created_at >= ? AND created_at < ?
				GROUP BY transaction_type
				""", closingId, Timestamp.valueOf(from), Timestamp.valueOf(to));
	}

	/**
	 * 영업일 거래 하나하나의 복식부기를 확인한다. 차변 합 = 대변 합 = 거래 금액이어야 한다.
	 * 분개가 하나도 없는 거래(LEFT JOIN 으로 합계 0)도 여기서 걸린다.
	 */
	public int insertUnbalancedTransactions(long closingId, LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update("""
				INSERT INTO closing_mismatch (id, closing_id, mismatch_type, account_id, transaction_id, expected, actual, detail)
				SELECT closing_mismatch_seq.NEXTVAL, ?, 'UNBALANCED_TRANSACTION', x.account_id, x.id, x.debit, x.credit,
				       '거래 금액 ' || x.amount || '원, 차변 합(expected)과 대변 합(actual)이 거래 금액과 같아야 합니다.'
				FROM (
				    SELECT t.id, t.account_id, t.amount,
				           COALESCE(SUM(CASE e.entry_side WHEN 'DEBIT' THEN e.amount END), 0) AS debit,
				           COALESCE(SUM(CASE e.entry_side WHEN 'CREDIT' THEN e.amount END), 0) AS credit
				    FROM ledger_transaction t LEFT JOIN ledger_entry e ON e.transaction_id = t.id
				    WHERE t.created_at >= ? AND t.created_at < ?
				    GROUP BY t.id, t.account_id, t.amount
				) x
				WHERE x.debit <> x.credit OR x.debit <> x.amount
				""", closingId, Timestamp.valueOf(from), Timestamp.valueOf(to));
	}

	/** 영업일 이체마다 원장 거래가 출금 쪽, 입금 쪽 2건인지 확인한다. */
	public int insertIncompleteTransfers(long closingId, LocalDateTime from, LocalDateTime to) {
		return jdbcTemplate.update("""
				INSERT INTO closing_mismatch (id, closing_id, mismatch_type, transfer_id, expected, actual, detail)
				SELECT closing_mismatch_seq.NEXTVAL, ?, 'INCOMPLETE_TRANSFER', x.id, 2, x.cnt,
				       '이체 한 건의 원장 거래는 출금 쪽과 입금 쪽 2건이어야 합니다.'
				FROM (
				    SELECT tr.id, (SELECT COUNT(*) FROM ledger_transaction t WHERE t.transfer_id = tr.id) AS cnt
				    FROM transfer tr
				    WHERE tr.created_at >= ? AND tr.created_at < ?
				) x
				WHERE x.cnt <> 2
				""", closingId, Timestamp.valueOf(from), Timestamp.valueOf(to));
	}

	/** 마감 시점까지의 이체 가계정 잔액이 0인지 확인한다. 0이 아니면 한 건으로 남긴다. */
	public int insertClearingNotZero(long closingId, LocalDateTime cutoff) {
		return jdbcTemplate.update("""
				INSERT INTO closing_mismatch (id, closing_id, mismatch_type, expected, actual, detail)
				SELECT closing_mismatch_seq.NEXTVAL, ?, 'CLEARING_NOT_ZERO', 0, x.balance,
				       '이체 가계정 잔액(차변 - 대변)은 모든 이체가 완결되면 0이어야 합니다.'
				FROM (
				    SELECT COALESCE(SUM(CASE e.entry_side WHEN 'DEBIT' THEN e.amount ELSE -e.amount END), 0) AS balance
				    FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
				    WHERE e.ledger_account = 'TRANSFER_CLEARING' AND t.created_at < ?
				) x
				WHERE x.balance <> 0
				""", closingId, Timestamp.valueOf(cutoff));
	}

	/** 계좌 대사 단계(청크)가 찾은 불일치를 한 번에 넣는다. */
	public void insertMismatches(long closingId, List<ClosingMismatch> mismatches) {
		jdbcTemplate.batchUpdate("""
				INSERT INTO closing_mismatch (id, closing_id, mismatch_type, account_id, transaction_id, transfer_id,
				                              expected, actual, detail)
				VALUES (closing_mismatch_seq.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?)
				""", mismatches, mismatches.size(), (ps, m) -> {
			ps.setLong(1, closingId);
			ps.setString(2, m.type().name());
			ps.setObject(3, m.accountId());
			ps.setObject(4, m.transactionId());
			ps.setObject(5, m.transferId());
			ps.setBigDecimal(6, m.expected());
			ps.setBigDecimal(7, m.actual());
			ps.setString(8, m.detail());
		});
	}

	// ───────────────────────── 조회 ─────────────────────────

	private static final String CLOSING_COLUMNS = """
			id, business_date, status, trigger_type, requested_by, job_execution_id, account_count,
			deposit_liability, mismatch_count, started_at, finished_at, error_message
			""";

	private static final RowMapper<DailyClosing> CLOSING_MAPPER = (rs, row) -> new DailyClosing(
			rs.getLong("id"),
			rs.getDate("business_date").toLocalDate(),
			ClosingStatus.valueOf(rs.getString("status")),
			ClosingTrigger.valueOf(rs.getString("trigger_type")),
			rs.getString("requested_by"),
			nullableLong(rs, "job_execution_id"),
			nullableLong(rs, "account_count"),
			rs.getBigDecimal("deposit_liability"),
			nullableLong(rs, "mismatch_count"),
			rs.getTimestamp("started_at").toLocalDateTime(),
			toLocalDateTime(rs.getTimestamp("finished_at")),
			rs.getString("error_message"));

	public Optional<DailyClosing> findById(long id) {
		return jdbcTemplate.query("SELECT " + CLOSING_COLUMNS + " FROM daily_closing WHERE id = ?", CLOSING_MAPPER, id)
				.stream().findFirst();
	}

	/** 최근 실행 순(id 내림차순) 목록. */
	public List<DailyClosing> findPage(int page, int size) {
		return jdbcTemplate.query("SELECT " + CLOSING_COLUMNS + """
				FROM daily_closing ORDER BY id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
				""", CLOSING_MAPPER, (long) page * size, size);
	}

	public long count() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM daily_closing", Long.class);
	}

	public List<DailyClosingTotal> findTotals(long closingId) {
		return jdbcTemplate.query("""
				SELECT transaction_type, tx_count, amount FROM daily_closing_total
				WHERE closing_id = ? ORDER BY transaction_type
				""", (rs, row) -> new DailyClosingTotal(
				TransactionType.valueOf(rs.getString("transaction_type")),
				rs.getLong("tx_count"),
				rs.getBigDecimal("amount")), closingId);
	}

	/** 불일치 내역. 수가 아주 많을 수 있어 limit 건까지만 읽는다. 전체 건수는 daily_closing.mismatch_count 에 있다. */
	public List<ClosingMismatch> findMismatches(long closingId, int limit) {
		return jdbcTemplate.query("""
				SELECT id, mismatch_type, account_id, transaction_id, transfer_id, expected, actual, detail
				FROM closing_mismatch WHERE closing_id = ? ORDER BY id FETCH FIRST ? ROWS ONLY
				""", (rs, row) -> new ClosingMismatch(
				rs.getLong("id"),
				MismatchType.valueOf(rs.getString("mismatch_type")),
				nullableLong(rs, "account_id"),
				nullableLong(rs, "transaction_id"),
				nullableLong(rs, "transfer_id"),
				rs.getBigDecimal("expected"),
				rs.getBigDecimal("actual"),
				rs.getString("detail")), closingId, limit);
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	static BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

}
