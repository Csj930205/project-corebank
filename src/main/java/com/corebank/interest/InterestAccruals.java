package com.corebank.interest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 일별 적수(interest_accrual). 계좌 수만큼 행을 한꺼번에 쓰고 합계를 내는 일이라 JPA 엔티티 대신 SQL 로 다룬다.
 * 엔티티로 하면 계좌마다 객체를 만들어 영속성 컨텍스트에 올렸다가 INSERT 를 한 줄씩 보낸다. 여기서는 INSERT … SELECT 한 문장이
 * DB 안에서 끝난다(일 마감의 일계표·불일치 점검과 같은 방식).
 */
@Repository
public class InterestAccruals {

	private final JdbcTemplate jdbc;

	public InterestAccruals(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 아직 지급하지 않은 적수 요약.
	 *
	 * @param balanceRateSum Σ(잔액 × 연이율%)
	 */
	public record Unpaid(LocalDate from, LocalDate to, int days, BigDecimal balanceRateSum) {
	}

	/**
	 * 영업일 하루치 적수를 쌓는다. 그날 마감 잔액은 "그날이 끝나기 전 마지막 거래의 거래 후 잔액" 이다
	 * (일 마감 대사의 마감 시점 잔액과 같은 기준). 지금 잔액(account.balance)을 쓰면 늦게 실행할수록 다음 날 거래가 섞인다.
	 *
	 * <p>대상: 그날이 끝나기 전에 열렸고 그날이 끝날 때 해지되지 않은 계좌 중 잔액과 이율이 0보다 큰 계좌.
	 * 이미 쌓인 날은 건너뛰므로(NOT EXISTS) 같은 날을 다시 실행해도 두 번 쌓이지 않는다.
	 *
	 * @return 새로 쌓은 계좌 수
	 */
	public int accrue(LocalDate date, LocalDateTime cutoff, LocalDateTime now) {
		Timestamp end = Timestamp.valueOf(cutoff);
		return jdbc.update("""
				INSERT INTO interest_accrual (account_id, accrual_date, balance, annual_rate, created_at)
				SELECT id, ?, balance, annual_rate, ? FROM (
				    SELECT a.id, p.annual_rate,
				           (SELECT MAX(t.balance_after) KEEP (DENSE_RANK LAST ORDER BY t.id)
				            FROM ledger_transaction t
				            WHERE t.account_id = a.id AND t.created_at < ?) AS balance
				    FROM account a JOIN deposit_product p ON p.id = a.product_id
				    WHERE a.created_at < ?
				      AND (a.closed_at IS NULL OR a.closed_at >= ?)
				      AND NOT EXISTS (SELECT 1 FROM interest_accrual i WHERE i.account_id = a.id AND i.accrual_date = ?)
				)
				WHERE balance > 0 AND annual_rate > 0
				""", Date.valueOf(date), Timestamp.valueOf(now), end, end, end, Date.valueOf(date));
	}

	/** upTo 이하 날짜 중 아직 지급하지 않은 적수. 없으면 비어 있다. */
	public Optional<Unpaid> unpaid(Long accountId, LocalDate upTo) {
		return jdbc.queryForObject("""
				SELECT MIN(accrual_date) AS from_date, MAX(accrual_date) AS to_date, COUNT(*) AS days,
				       SUM(balance * annual_rate) AS balance_rate_sum
				FROM interest_accrual
				WHERE account_id = ? AND payment_id IS NULL AND accrual_date <= ?
				""", (rs, row) -> rs.getInt("days") == 0
				? Optional.<Unpaid>empty()
				: Optional.of(new Unpaid(rs.getDate("from_date").toLocalDate(), rs.getDate("to_date").toLocalDate(),
						rs.getInt("days"), rs.getBigDecimal("balance_rate_sum"))),
				accountId, Date.valueOf(upTo));
	}

	/** 적수에 지급 번호를 채운다. 지급한 적수는 다시 지급되지 않는다. @return 바뀐 행 수 */
	public int markPaid(Long accountId, LocalDate upTo, Long paymentId) {
		return jdbc.update("""
				UPDATE interest_accrual SET payment_id = ?
				WHERE account_id = ? AND payment_id IS NULL AND accrual_date <= ?
				""", paymentId, accountId, Date.valueOf(upTo));
	}

}
