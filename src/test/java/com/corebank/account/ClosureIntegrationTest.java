package com.corebank.account;

import com.corebank.TestcontainersConfiguration;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotencyKeyReusedException;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.ledger.LedgerTransaction;
import com.corebank.ledger.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
 * 계좌 해지 통합 테스트. 실제 Oracle 에서 잔액 지급, 원장, 멱등성, 동시성, DB 제약을 검증한다. Docker 가 필요하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClosureIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long openWithBalance(String name, long balance) {
		Long id = accountService.open(name).getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey());
		}
		return id;
	}

	private long customerDepositBalance(Long id) {
		return jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE account_id = ?
				""", Long.class, id);
	}

	private long payoutCount(Long id) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ? AND transaction_type = 'CLOSURE_PAYOUT'",
				Long.class, id);
	}

	/** 남은 잔액은 해지 지급 거래로 나가고, 계좌는 잔액 0, CLOSED, 해지 시각이 남는다. */
	@Test
	void closePaysOutRemainingBalance() {
		Long id = openWithBalance("홍길동", 10_000);

		AccountClosure closure = accountService.close(id, newKey()).value();

		assertThat(closure.payoutAmount()).isEqualTo(10_000);
		assertThat(closure.payout().getType()).isEqualTo(TransactionType.CLOSURE_PAYOUT);
		assertThat(closure.payout().getBalanceAfter()).isZero();
		Account account = accountService.get(id);
		assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
		assertThat(account.getBalance()).isZero();
		assertThat(account.getClosedAt()).isNotNull();
	}

	/** 해지 지급은 출금과 같은 분개다. 지급 후 고객 예금 원장 합계가 계좌 잔액(0)과 맞는다. */
	@Test
	void closeWritesBalancedPayoutEntries() {
		Long id = openWithBalance("김철수", 10_000);

		LedgerTransaction payout = accountService.close(id, newKey()).value().payout();

		List<String> entries = jdbcTemplate.queryForList("""
				SELECT ledger_account || ':' || entry_side || ':' || amount
				FROM ledger_entry WHERE transaction_id = ? ORDER BY ledger_account
				""", String.class, payout.getId());
		assertThat(entries).containsExactly("CASH:CREDIT:10000", "CUSTOMER_DEPOSIT:DEBIT:10000");
		assertThat(customerDepositBalance(id)).isZero();
	}

	/** 잔액 0이면 움직인 돈이 없으므로 원장 거래 없이 상태만 바뀐다. */
	@Test
	void closeWithZeroBalanceWritesNoLedger() {
		Long id = openWithBalance("빈계좌", 0);

		AccountClosure closure = accountService.close(id, newKey()).value();

		assertThat(closure.payout()).isNull();
		assertThat(closure.payoutAmount()).isZero();
		assertThat(accountService.get(id).getStatus()).isEqualTo(AccountStatus.CLOSED);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ?", Long.class, id)).isZero();
	}

	/** 새 키로 다시 해지하면 거부된다. 지급도 한 번뿐이다. */
	@Test
	void closeTwiceWithNewKeyThrows() {
		Long id = openWithBalance("두번해지", 10_000);
		accountService.close(id, newKey());

		assertThatThrownBy(() -> accountService.close(id, newKey()))
				.isInstanceOf(AccountClosedException.class);
		assertThat(payoutCount(id)).isEqualTo(1);
	}

	/** 같은 키로 다시 보내면 409 가 아니라 처음 결과(같은 지급 거래)를 돌려준다. 응답을 잃어버린 클라이언트의 재시도다. */
	@Test
	void closeWithSameKeyReplaysFirstResult() {
		Long id = openWithBalance("재전송", 10_000);
		IdempotencyKey key = newKey();
		IdempotentResult<AccountClosure> first = accountService.close(id, key);

		IdempotentResult<AccountClosure> second = accountService.close(id, key);

		assertThat(first.replayed()).isFalse();
		assertThat(second.replayed()).isTrue();
		assertThat(second.value().payout().getId()).isEqualTo(first.value().payout().getId());
		assertThat(second.value().payoutAmount()).isEqualTo(10_000);
		assertThat(second.value().account().getClosedAt()).isEqualTo(first.value().account().getClosedAt());
		assertThat(payoutCount(id)).isEqualTo(1);
	}

	/** 잔액 0 해지도 같은 키 재전송이 된다. 지급 거래가 없으므로 결과도 지급 없음이다. */
	@Test
	void closeZeroBalanceWithSameKeyReplays() {
		Long id = openWithBalance("빈재전송", 0);
		IdempotencyKey key = newKey();
		accountService.close(id, key);

		IdempotentResult<AccountClosure> second = accountService.close(id, key);

		assertThat(second.replayed()).isTrue();
		assertThat(second.value().payout()).isNull();
	}

	/** 입금에 쓴 키로 해지하면 키 재사용으로 거부되고 계좌는 그대로다. */
	@Test
	void closeWithKeyUsedForDepositIsRejected() {
		Long id = accountService.open("키재사용").getId();
		IdempotencyKey key = newKey();
		accountService.deposit(id, 10_000, key);

		assertThatThrownBy(() -> accountService.close(id, key))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(accountService.get(id).getStatus()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(accountService.get(id).getBalance()).isEqualTo(10_000);
	}

	@Test
	void closeMissingAccountThrows() {
		assertThatThrownBy(() -> accountService.close(-1L, newKey()))
				.isInstanceOf(AccountNotFoundException.class);
	}

	/** 거래내역에 해지 지급이 나가는 돈(OUT)으로 보인다. */
	@Test
	void historyShowsClosurePayout() {
		Long id = openWithBalance("내역", 10_000);
		accountService.close(id, newKey());

		TransactionHistoryResponse.Item latest = accountService.history(id, null, 20).transactions().getFirst();

		assertThat(latest.type()).isEqualTo(TransactionType.CLOSURE_PAYOUT);
		assertThat(latest.direction()).isEqualTo(TransactionHistoryResponse.Direction.OUT);
		assertThat(latest.amount()).isEqualTo(10_000);
		assertThat(latest.balanceAfter()).isZero();
	}

	/**
	 * 입금 20건과 해지가 동시에 들어와도 돈이 사라지지 않는다. 해지보다 먼저 처리된 입금은 지급액에 포함되고,
	 * 나중 입금은 ACCOUNT_CLOSED 로 거부된다. 잠금이 없으면 해지가 잔액을 0으로 덮어쓰면서 입금이 사라질 수 있다.
	 */
	@Test
	void concurrentDepositsAndCloseNeverLoseMoney() throws Exception {
		Long id = openWithBalance("동시해지", 1_000);
		List<Callable<Long>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> {
				accountService.deposit(id, 100, newKey());
				return 100L;
			});
		}
		tasks.add(10, () -> -accountService.close(id, newKey()).value().payoutAmount());

		long deposited = 1_000;
		long paidOut = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			for (Future<Long> future : executor.invokeAll(tasks)) {
				try {
					long result = future.get();
					if (result > 0) {
						deposited += result;
					} else {
						paidOut = -result;
					}
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(AccountClosedException.class);
				}
			}
		}

		assertThat(paidOut).isEqualTo(deposited);
		assertThat(accountService.get(id).getBalance()).isZero();
		assertThat(customerDepositBalance(id)).isZero();
	}

	/** 코드에 버그가 있어도 DB 가 해지 계좌에 잔액이 남는 것을 막는다. */
	@Test
	void databaseRejectsBalanceOnClosedAccount() {
		Long id = openWithBalance("제약", 0);
		accountService.close(id, newKey());

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE account SET balance = 1 WHERE id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE account SET status = 'CLOSED' WHERE id = ?",
				openWithBalance("시각없음", 0)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 해지 지급은 계좌당 한 건뿐이다. 두 번째 지급 행은 DB 의 유니크 인덱스가 막는다. */
	@Test
	void databaseRejectsSecondClosurePayout() {
		Long id = openWithBalance("지급중복", 10_000);
		accountService.close(id, newKey());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO ledger_transaction (id, transaction_type, account_id, amount, balance_after, created_at)
				VALUES (ledger_transaction_seq.NEXTVAL, 'CLOSURE_PAYOUT', ?, 1, 0, SYSTIMESTAMP)
				""", id))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
