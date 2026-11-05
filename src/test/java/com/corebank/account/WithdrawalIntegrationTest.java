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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 출금 통합 테스트. 실제 Oracle 에서 잔액, 원장, 동시성을 검증한다. Docker 가 필요하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WithdrawalIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 출금하면 잔액이 줄고 거래 기록에 출금 후 잔액이 남는다. */
	@Test
	void withdrawUpdatesBalanceAndRecordsTransaction() {
		Long id = accountService.open("홍길동").getId();
		accountService.deposit(id, 10_000, newKey()).value();

		LedgerTransaction transaction = accountService.withdraw(id, 3_000, newKey()).value();

		assertThat(transaction.getBalanceAfter()).isEqualTo(7_000);
		assertThat(accountService.get(id).getBalance()).isEqualTo(7_000);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT transaction_type FROM ledger_transaction WHERE id = ?", String.class, transaction.getId()))
				.isEqualTo("WITHDRAWAL");
	}

	/** 출금 한 건은 고객 예금 차변 1줄과 현금 대변 1줄로, 입금과 방향이 반대다. */
	@Test
	void withdrawWritesBalancedEntries() {
		Long id = accountService.open("김철수").getId();
		accountService.deposit(id, 10_000, newKey()).value();

		LedgerTransaction transaction = accountService.withdraw(id, 3_000, newKey()).value();

		List<String> entries = jdbcTemplate.queryForList("""
				SELECT ledger_account || ':' || entry_side || ':' || amount
				FROM ledger_entry WHERE transaction_id = ? ORDER BY ledger_account
				""", String.class, transaction.getId());
		assertThat(entries).containsExactly("CASH:CREDIT:3000", "CUSTOMER_DEPOSIT:DEBIT:3000");
	}

	/** 잔액 전부를 출금할 수 있다. DB 의 balance >= 0 제약에도 걸리지 않는다. */
	@Test
	void withdrawExactBalanceLeavesZero() {
		Long id = accountService.open("전액출금").getId();
		accountService.deposit(id, 10_000, newKey()).value();

		accountService.withdraw(id, 10_000, newKey()).value();

		assertThat(accountService.get(id).getBalance()).isZero();
	}

	/** 잔액이 부족하면 거부되고 잔액과 원장이 그대로다. */
	@Test
	void withdrawRejectsInsufficientBalance() {
		Long id = accountService.open("잔액부족").getId();
		accountService.deposit(id, 10_000, newKey()).value();

		assertThatThrownBy(() -> accountService.withdraw(id, 10_001, newKey()).value())
				.isInstanceOf(InsufficientBalanceException.class);

		assertThat(accountService.get(id).getBalance()).isEqualTo(10_000);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ? AND transaction_type = 'WITHDRAWAL'",
				Long.class, id)).isZero();
	}

	@Test
	void withdrawFromMissingAccountThrows() {
		assertThatThrownBy(() -> accountService.withdraw(-1L, 1000, newKey()).value())
				.isInstanceOf(AccountNotFoundException.class);
	}

	/** 해지 계좌 출금은 거부되고 출금 거래가 생기지 않는다. */
	@Test
	void withdrawFromClosedAccountThrows() {
		Long id = accountService.open("이영희").getId();
		accountService.deposit(id, 10_000, newKey()).value();
		accountService.close(id, newKey());

		assertThatThrownBy(() -> accountService.withdraw(id, 1000, newKey()).value())
				.isInstanceOf(AccountClosedException.class);

		assertThat(accountService.get(id).getBalance()).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ? AND transaction_type = 'WITHDRAWAL'",
				Long.class, id)).isZero();
	}

	/**
	 * 잔액 10,000원 계좌에 3,000원 출금 10건이 동시에 들어오면 정확히 3건만 성공하고 잔액은 1,000원이어야 한다.
	 * 락이 없으면 여러 요청이 같은 잔액을 읽고 동시에 통과해, 성공 건수가 늘거나 차감이 사라진다.
	 */
	@Test
	void concurrentWithdrawalsNeverOverdraw() throws Exception {
		Long id = accountService.open("동시출금").getId();
		accountService.deposit(id, 10_000, newKey()).value();
		int count = 10;
		List<Callable<LedgerTransaction>> tasks = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			tasks.add(() -> accountService.withdraw(id, 3_000, newKey()).value());
		}

		int succeeded = 0;
		int insufficient = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			for (Future<LedgerTransaction> future : executor.invokeAll(tasks)) {
				try {
					future.get();
					succeeded++;
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(InsufficientBalanceException.class);
					insufficient++;
				}
			}
		}

		assertThat(succeeded).isEqualTo(3);
		assertThat(insufficient).isEqualTo(7);
		assertThat(accountService.get(id).getBalance()).isEqualTo(1_000);
		// 계좌 잔액은 고객 예금 분개의 (대변 - 차변) 합계와 같아야 한다.
		assertThat(jdbcTemplate.queryForObject("""
				SELECT SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END)
				FROM ledger_entry WHERE account_id = ?
				""", Long.class, id)).isEqualTo(1_000);
		// 전체 원장의 차변 합과 대변 합은 항상 같아야 한다.
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry
				""", Long.class)).isZero();
	}

}
