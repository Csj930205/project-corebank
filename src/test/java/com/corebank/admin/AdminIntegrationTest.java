package com.corebank.admin;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.Account;
import com.corebank.account.AccountService;
import com.corebank.account.AccountStatus;
import com.corebank.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 조회 통합 테스트. 다른 테스트가 같은 DB 에 계좌를 계속 만들기 때문에,
 * 테스트마다 고유한 이름표(tag)를 붙인 계좌를 만들어 그 계좌들만 검색해 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AdminIntegrationTest {

	@Autowired
	private AdminQueryService adminQueryService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static String tag() {
		return "t" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** 최신 개설순으로, size 씩 나뉘고 전체 건수와 쪽 수가 맞다. */
	@Test
	void listsNewestFirstWithPaging() {
		String tag = tag();
		for (int i = 1; i <= 5; i++) {
			accountService.open(tag + "-" + i);
		}

		Page<Account> first = adminQueryService.searchAccounts(tag, null, 0, 2);
		Page<Account> last = adminQueryService.searchAccounts(tag, null, 2, 2);

		assertThat(first.getTotalElements()).isEqualTo(5);
		assertThat(first.getTotalPages()).isEqualTo(3);
		assertThat(first.getContent()).extracting(Account::getOwnerName).containsExactly(tag + "-5", tag + "-4");
		assertThat(last.getContent()).extracting(Account::getOwnerName).containsExactly(tag + "-1");
	}

	/** 예금주 이름은 부분 일치, 대소문자 구분 없음 */
	@Test
	void searchesOwnerNamePartially() {
		String tag = tag();
		accountService.open("Kim-" + tag + "-alpha");
		accountService.open("Lee-" + tag + "-beta");

		assertThat(adminQueryService.searchAccounts(tag.toUpperCase() + "-ALPHA", null, 0, 20).getContent())
				.extracting(Account::getOwnerName).containsExactly("Kim-" + tag + "-alpha");
		assertThat(adminQueryService.searchAccounts(tag, null, 0, 20).getTotalElements()).isEqualTo(2);
	}

	/** 계좌번호는 앞자리 일치 */
	@Test
	void searchesAccountNumberByPrefix() {
		Account account = accountService.open(tag());

		Page<Account> found = adminQueryService.searchAccounts(account.getAccountNumber(), null, 0, 20);

		assertThat(found.getContent()).extracting(Account::getId).containsExactly(account.getId());
	}

	/** 상태로 거른다. */
	@Test
	void filtersByStatus() {
		String tag = tag();
		Account active = accountService.open(tag + "-active");
		Account closed = accountService.open(tag + "-closed");
		accountService.close(closed.getId(), newKey());

		assertThat(adminQueryService.searchAccounts(tag, AccountStatus.CLOSED, 0, 20).getContent())
				.extracting(Account::getId).containsExactly(closed.getId());
		assertThat(adminQueryService.searchAccounts(tag, AccountStatus.ACTIVE, 0, 20).getContent())
				.extracting(Account::getId).containsExactly(active.getId());
	}

	/** 검색어의 % 와 _ 는 와일드카드가 아니라 글자 그대로다. "%" 검색이 모든 계좌와 맞으면 안 된다. */
	@Test
	void likeWildcardsAreEscaped() {
		String tag = tag();
		accountService.open(tag + "_50%할인");
		accountService.open(tag + "X50Y할인");

		assertThat(adminQueryService.searchAccounts(tag + "_50%", null, 0, 20).getContent())
				.extracting(Account::getOwnerName).containsExactly(tag + "_50%할인");
		assertThat(AdminQueryService.escapeLike("a%b_c\\")).isEqualTo("a\\%b\\_c\\\\");
	}

	/** 요약 숫자가 DB 와 맞고, 정상 거래만 있으면 원장 점검은 balanced 다. */
	@Test
	void summaryMatchesDatabaseAndLedgerIsBalanced() {
		Account a = accountService.open(tag());
		Account b = accountService.open(tag());
		accountService.deposit(a.getId(), 10_000, newKey());
		transferService.transfer(a.getId(), b.getId(), 3_000, newKey());

		AdminSummary summary = adminQueryService.summary();

		assertThat(summary.accountCount()).isEqualTo(
				jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account", Long.class));
		assertThat(summary.activeAccountCount() + summary.closedAccountCount()).isEqualTo(summary.accountCount());
		assertThat(new BigDecimal(summary.totalBalance())).isEqualByComparingTo(
				jdbcTemplate.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM account", BigDecimal.class));
		assertThat(summary.transactionCount()).isEqualTo(
				jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class));
		assertThat(summary.todayTransactionCount()).isGreaterThanOrEqualTo(3);
		// "오늘" 은 앱 시각 기준이다. DB(UTC) 의 SYSDATE 로 세면 한국 시각 0~9시에 어제 거래까지 섞인다.
		assertThat(summary.todayTransactionCount()).isEqualTo(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE created_at >= ?", Long.class,
				java.sql.Timestamp.valueOf(java.time.LocalDate.now().atStartOfDay())));
		assertThat(summary.ledger().balanced()).isTrue();
		assertThat(summary.ledger().mismatchedAccounts()).isZero();
	}

	/** 잔액 합계가 long 범위를 넘어도 정확하다. 한도 시험용 계좌(long 최대값) 두 개를 만든다. */
	@Test
	void totalBalanceDoesNotOverflow() {
		accountService.deposit(accountService.open(tag()).getId(), Long.MAX_VALUE, newKey());
		accountService.deposit(accountService.open(tag()).getId(), Long.MAX_VALUE, newKey());

		BigDecimal total = new BigDecimal(adminQueryService.summary().totalBalance());

		assertThat(total).isGreaterThan(BigDecimal.valueOf(Long.MAX_VALUE));
	}

	/** 원장을 SQL 로 몰래 고치면 점검이 잡아낸다. 확인 뒤 되돌린다. */
	@Test
	void summaryDetectsTamperedBalance() {
		Account account = accountService.open(tag());
		accountService.deposit(account.getId(), 5_000, newKey());
		jdbcTemplate.update("UPDATE account SET balance = balance + 1 WHERE id = ?", account.getId());
		try {
			AdminSummary.LedgerCheck check = adminQueryService.summary().ledger();

			assertThat(check.balanced()).isFalse();
			assertThat(check.mismatchedAccounts()).isGreaterThanOrEqualTo(1);
		} finally {
			jdbcTemplate.update("UPDATE account SET balance = balance - 1 WHERE id = ?", account.getId());
		}
	}

}
