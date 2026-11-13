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
	 * <p>대상: 그날이 끝나기 전에 열렸고 그날이 끝날 때 해지되지 않은 <b>입출금</b> 계좌 중 잔액과 이율이 0보다 큰 계좌.
	 * 정기예금은 적수를 쌓지 않는다(원금이 바뀌지 않아 만기·중도해지 때 한 번에 계산한다, TimeDepositService).
	 * 이미 쌓인 날은 건너뛰므로(NOT EXISTS) 같은 날을 다시 실행해도 두 번 쌓이지 않는다.
	 *
	 * <p>이율은 상품의 "지금" 금리가 아니라 금리 이력에서 그날 적용된 금리다(그날 이전에 시작한 금리 중 가장 늦은 것,
	 * 같은 날 여러 번 바꿨으면 마지막 것). 지금 금리를 쓰면 적립이 늦게 돌았을 때(서버 장애 뒤 몰아서 실행) 지난 날짜에
	 * 바뀐 뒤의 금리가 붙는다.
	 *
	 * @return 새로 쌓은 계좌 수
	 */
	public int accrue(LocalDate date, LocalDateTime cutoff, LocalDateTime now) {
		Timestamp end = Timestamp.valueOf(cutoff);
		return jdbc.update("""
				INSERT INTO interest_accrual (account_id, accrual_date, balance, annual_rate, created_at)
				SELECT id, ?, balance, annual_rate, ? FROM (
				    SELECT a.id,
				           (SELECT MAX(r.annual_rate) KEEP (DENSE_RANK LAST ORDER BY r.effective_from, r.id)
				            FROM deposit_product_rate r
				            WHERE r.product_id = a.product_id AND r.effective_from <= ?) AS annual_rate,
				           (SELECT MAX(t.balance_after) KEEP (DENSE_RANK LAST ORDER BY t.id)
				            FROM ledger_transaction t
				            WHERE t.account_id = a.id AND t.created_at < ?) AS balance
				    FROM account a JOIN deposit_product p ON p.id = a.product_id
				    WHERE p.product_type = 'DEMAND'
				      AND a.created_at < ?
				      AND (a.closed_at IS NULL OR a.closed_at >= ?)
				      AND NOT EXISTS (SELECT 1 FROM interest_accrual i WHERE i.account_id = a.id AND i.accrual_date = ?)
				)
				-- 그날 이전에 금리 이력이 없으면(상품이 그날보다 늦게 생김) annual_rate 가 null 이라 빠진다.
				WHERE balance > 0 AND annual_rate > 0
				""", Date.valueOf(date), Timestamp.valueOf(now), Date.valueOf(date), end, end, end, Date.valueOf(date));
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
