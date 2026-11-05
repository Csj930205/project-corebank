package com.corebank.account;

import com.corebank.TestcontainersConfiguration;
import com.corebank.ledger.LedgerTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 입금 통합 테스트. 실제 Oracle 에서 잔액, 원장, 동시성을 검증한다. Docker 가 필요하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DepositIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 입금하면 잔액과 거래 기록이 함께 반영된다. */
	@Test
	void depositUpdatesBalanceAndRecordsTransaction() {
		Account account = accountService.open("홍길동");

		LedgerTransaction first = accountService.deposit(account.getId(), 10_000, newKey()).value();
		LedgerTransaction second = accountService.deposit(account.getId(), 5_000, newKey()).value();

		assertThat(first.getBalanceAfter()).isEqualTo(10_000);
		assertThat(second.getBalanceAfter()).isEqualTo(15_000);
		assertThat(accountService.get(account.getId()).getBalance()).isEqualTo(15_000);
	}

	/** 입금 한 건은 현금 차변 1줄과 고객 예금 대변 1줄로 기록되고, 금액이 같다. */
	@Test
	void depositWritesBalancedEntries() {
		Account account = accountService.open("김철수");

		LedgerTransaction transaction = accountService.deposit(account.getId(), 10_000, newKey()).value();

		List<String> entries = jdbcTemplate.queryForList("""
				SELECT ledger_account || ':' || entry_side || ':' || amount
				FROM ledger_entry WHERE transaction_id = ? ORDER BY ledger_account
				""", String.class, transaction.getId());
		assertThat(entries).containsExactly("CASH:DEBIT:10000", "CUSTOMER_DEPOSIT:CREDIT:10000");
	}

	/** 없는 계좌에 입금하면 예외가 나고 원장에 아무것도 남지 않는다. */
	@Test
	void depositToMissingAccountThrows() {
		assertThatThrownBy(() -> accountService.deposit(-1L, 1000, newKey()).value())
				.isInstanceOf(AccountNotFoundException.class);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = -1", Long.class)).isZero();
	}

	/** 해지 계좌 입금은 거부되고 잔액과 원장이 그대로다. 잔액 0으로 해지해서 원장에는 아무 거래도 없다. */
	@Test
	void depositToClosedAccountThrows() {
		Long id = accountService.open("이영희").getId();
		accountService.close(id, newKey());

		assertThatThrownBy(() -> accountService.deposit(id, 1000, newKey()).value())
				.isInstanceOf(AccountClosedException.class);

		assertThat(accountService.get(id).getBalance()).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ?", Long.class, id)).isZero();
	}

	/** 같은 계좌에 동시에 입금해도 금액이 사라지지 않는다. 락이 없으면 서로의 잔액을 덮어써 실패하는 테스트다. */
	@Test
	void concurrentDepositsAreAllApplied() throws Exception {
		Long id = accountService.open("동시입금").getId();
		int count = 20;
		List<Callable<LedgerTransaction>> tasks = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			tasks.add(() -> accountService.deposit(id, 1000, newKey()).value());
		}

		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			for (Future<LedgerTransaction> future : executor.invokeAll(tasks)) {
				future.get();
			}
		}

		assertThat(accountService.get(id).getBalance()).isEqualTo(count * 1000L);
		// 잔액은 해당 계좌의 고객 예금 분개 합계와 같아야 한다.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT SUM(amount) FROM ledger_entry WHERE account_id = ? AND entry_side = 'CREDIT'",
				Long.class, id)).isEqualTo(count * 1000L);
		// 전체 원장의 차변 합과 대변 합은 항상 같아야 한다.
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry
				""", Long.class)).isZero();
	}

}
