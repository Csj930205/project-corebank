package com.corebank.admin;

import com.corebank.account.Account;
import com.corebank.account.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 관리자 화면용 조회. 쓰기는 하지 않는다.
 */
@Service
public class AdminQueryService {

	private final AccountRepository accountRepository;
	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public AdminQueryService(AccountRepository accountRepository, JdbcTemplate jdbcTemplate, Clock clock) {
		this.accountRepository = accountRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	/**
	 * 계좌 목록. 최신 개설순(id 내림차순)이고, 쪽 번호 방식이다.
	 *
	 * <p>고객 거래내역은 커서 방식이지만 관리자 목록은 "전체 몇 건, 몇 쪽" 이 필요해 쪽 번호 방식을 쓴다.
	 * 쪽 번호 방식은 COUNT 쿼리가 한 번 더 나가고, 뒤쪽 페이지일수록 느려진다. 계좌가 아주 많아지면 바꿔야 한다.
	 */
	@Transactional(readOnly = true)
	public Page<Account> searchAccounts(AccountSearch search, int page, int size) {
		String query = search.query();
		String q = query == null || query.isBlank() ? null : query.trim();
		String numberPrefix = q == null ? null : escapeLike(q) + "%";
		String namePattern = q == null ? null : "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
		return accountRepository.search(numberPrefix, namePattern, search.status(),
				search.opened().startInclusive(), search.opened().endExclusive(),
				search.minBalance(), search.maxBalance(), pageable);
	}

	/** LIKE 의 특수 문자(%, _)와 이스케이프 문자 자신을 글자 그대로 찾도록 바꾼다. "50%" 검색이 모든 이름과 맞으면 안 된다. */
	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/**
	 * 관리자 첫 화면 요약. 계좌 현황과 원장 점검(대사) 결과를 한 번에 돌려준다.
	 *
	 * <p>잔액 합계는 long 범위를 넘을 수 있어(한도 시험용 계좌 등) BigDecimal 로 받는다.
	 */
	@Transactional(readOnly = true)
	public AdminSummary summary() {
		var accounts = jdbcTemplate.queryForMap("""
				SELECT COUNT(*) AS total,
				       COUNT(CASE WHEN status = 'ACTIVE' THEN 1 END) AS active,
				       COUNT(CASE WHEN status = 'CLOSED' THEN 1 END) AS closed,
				       COALESCE(SUM(balance), 0) AS total_balance
				FROM account
				""");
		// "오늘" 은 앱 시각(서버 시간대)으로 정한다. DB 의 SYSDATE 를 쓰면 안 된다. Oracle 컨테이너는 UTC 라
		// 한국 시각 0~9시에는 DB 날짜가 아직 전날이어서, 어제 거래까지 오늘로 센다. created_at 도 앱 시각으로 저장된다.
		var transactions = jdbcTemplate.queryForMap("""
				SELECT COUNT(*) AS total,
				       COUNT(CASE WHEN created_at >= ? THEN 1 END) AS today
				FROM ledger_transaction
				""", Timestamp.valueOf(LocalDate.now(clock).atStartOfDay()));
		long transfers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfer", Long.class);

		// 원장 점검 세 가지. 모두 0 이어야 정상이다.
		BigDecimal debitMinusCredit = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'DEBIT' THEN amount ELSE -amount END), 0) FROM ledger_entry
				""", BigDecimal.class);
		BigDecimal clearingBalance = jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE ledger_account = 'TRANSFER_CLEARING'
				""", BigDecimal.class);
		long mismatchedAccounts = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account a
				WHERE a.balance <> COALESCE((SELECT SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END)
				                             FROM ledger_entry e WHERE e.account_id = a.id), 0)
				""", Long.class);

		return new AdminSummary(
				number(accounts.get("TOTAL")).longValue(),
				number(accounts.get("ACTIVE")).longValue(),
				number(accounts.get("CLOSED")).longValue(),
				number(accounts.get("TOTAL_BALANCE")).toPlainString(),
				number(transactions.get("TOTAL")).longValue(),
				number(transactions.get("TODAY")).longValue(),
				transfers,
				new AdminSummary.LedgerCheck(
						debitMinusCredit.signum() == 0 && clearingBalance.signum() == 0 && mismatchedAccounts == 0,
						debitMinusCredit.toPlainString(),
						clearingBalance.toPlainString(),
						mismatchedAccounts));
	}

	/** Oracle NUMBER 는 JDBC 에서 BigDecimal 로 온다. */
	private static BigDecimal number(Object value) {
		return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
	}

}
