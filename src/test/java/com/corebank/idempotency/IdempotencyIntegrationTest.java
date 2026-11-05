package com.corebank.idempotency;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountService;
import com.corebank.account.InsufficientBalanceException;
import com.corebank.ledger.LedgerTransaction;
import com.corebank.transfer.TransferResult;
import com.corebank.transfer.TransferService;
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
 * 멱등성 키 통합 테스트. 같은 요청을 다시 보내거나 동시에 여러 번 보내도 돈은 한 번만 움직여야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long openWithBalance(String name, long balance) {
		Long id = accountService.open(name).getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey());
		}
		return id;
	}

	private long balance(Long id) {
		return accountService.get(id).getBalance();
	}

	private long ledgerCount(Long accountId, String type) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ? AND transaction_type = ?",
				Long.class, accountId, type);
	}

	/** 같은 키로 입금을 두 번 보내면 한 번만 입금되고, 두 번째는 처음 거래를 그대로 돌려준다. */
	@Test
	void repeatedDepositIsAppliedOnce() {
		Long id = openWithBalance("입금재전송", 0);
		IdempotencyKey key = newKey();

		IdempotentResult<LedgerTransaction> first = accountService.deposit(id, 10_000, key);
		IdempotentResult<LedgerTransaction> second = accountService.deposit(id, 10_000, key);

		assertThat(first.replayed()).isFalse();
		assertThat(second.replayed()).isTrue();
		assertThat(second.value().getId()).isEqualTo(first.value().getId());
		assertThat(second.value().getBalanceAfter()).isEqualTo(10_000);
		assertThat(balance(id)).isEqualTo(10_000);
		assertThat(ledgerCount(id, "DEPOSIT")).isEqualTo(1);
	}

	/**
	 * 다시 보낸 요청의 응답은 "지금 잔액"이 아니라 처음 처리했을 때의 거래 후 잔액이다.
	 * 그 사이 다른 입금이 있어도 첫 응답과 같은 내용을 받는다.
	 */
	@Test
	void replayReturnsOriginalBalanceAfter() {
		Long id = openWithBalance("원래잔액", 0);
		IdempotencyKey key = newKey();
		accountService.deposit(id, 10_000, key);
		accountService.deposit(id, 5_000, newKey());

		IdempotentResult<LedgerTransaction> replay = accountService.deposit(id, 10_000, key);

		assertThat(replay.value().getBalanceAfter()).isEqualTo(10_000);
		assertThat(balance(id)).isEqualTo(15_000);
	}

	@Test
	void repeatedWithdrawalIsAppliedOnce() {
		Long id = openWithBalance("출금재전송", 10_000);
		IdempotencyKey key = newKey();

		accountService.withdraw(id, 3_000, key);
		IdempotentResult<LedgerTransaction> second = accountService.withdraw(id, 3_000, key);

		assertThat(second.replayed()).isTrue();
		assertThat(balance(id)).isEqualTo(7_000);
		assertThat(ledgerCount(id, "WITHDRAWAL")).isEqualTo(1);
	}

	/** 이체를 다시 보내면 같은 이체 번호와 그때의 두 계좌 잔액을 돌려준다. */
	@Test
	void repeatedTransferIsAppliedOnce() {
		Long from = openWithBalance("이체재전송보냄", 10_000);
		Long to = openWithBalance("이체재전송받음", 0);
		IdempotencyKey key = newKey();

		IdempotentResult<TransferResult> first = transferService.transfer(from, to, 3_000, key);
		IdempotentResult<TransferResult> second = transferService.transfer(from, to, 3_000, key);

		assertThat(second.replayed()).isTrue();
		assertThat(second.value().transfer().getId()).isEqualTo(first.value().transfer().getId());
		assertThat(second.value().fromBalanceAfter()).isEqualTo(7_000);
		assertThat(second.value().toBalanceAfter()).isEqualTo(3_000);
		assertThat(balance(from)).isEqualTo(7_000);
		assertThat(balance(to)).isEqualTo(3_000);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM transfer WHERE from_account_id = ?", Long.class, from)).isEqualTo(1);
	}

	/** 같은 키로 금액이 다른 요청은 처음 결과를 돌려주지 않고 거부한다. */
	@Test
	void sameKeyWithDifferentAmountIsRejected() {
		Long id = openWithBalance("금액변경", 0);
		IdempotencyKey key = newKey();
		accountService.deposit(id, 10_000, key);

		assertThatThrownBy(() -> accountService.deposit(id, 20_000, key))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(balance(id)).isEqualTo(10_000);
	}

	/** 키는 작업 종류를 넘어서도 한 번만 쓸 수 있다. 입금에 쓴 키로 출금하면 거부한다. */
	@Test
	void sameKeyForDifferentOperationIsRejected() {
		Long id = openWithBalance("작업변경", 0);
		IdempotencyKey key = newKey();
		accountService.deposit(id, 10_000, key);

		assertThatThrownBy(() -> accountService.withdraw(id, 10_000, key))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(balance(id)).isEqualTo(10_000);
	}

	/** 같은 키로 다른 계좌에 입금하는 것도 거부한다. */
	@Test
	void sameKeyForDifferentAccountIsRejected() {
		Long a = openWithBalance("계좌변경A", 0);
		Long b = openWithBalance("계좌변경B", 0);
		IdempotencyKey key = newKey();
		accountService.deposit(a, 10_000, key);

		assertThatThrownBy(() -> accountService.deposit(b, 10_000, key))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(balance(b)).isZero();
	}

	/**
	 * 업무 오류로 실패한 요청은 기록이 남지 않는다. 잔액 부족으로 실패한 출금을 같은 키로 다시 보내면,
	 * 그 사이 잔액이 채워졌을 경우 이번에는 처리된다.
	 */
	@Test
	void failedRequestCanBeRetriedWithSameKey() {
		Long id = openWithBalance("실패후재시도", 1_000);
		IdempotencyKey key = newKey();

		assertThatThrownBy(() -> accountService.withdraw(id, 5_000, key))
				.isInstanceOf(InsufficientBalanceException.class);
		accountService.deposit(id, 10_000, newKey());
		IdempotentResult<LedgerTransaction> retry = accountService.withdraw(id, 5_000, key);

		assertThat(retry.replayed()).isFalse();
		assertThat(balance(id)).isEqualTo(6_000);
	}

	/**
	 * 같은 키의 입금 10건이 동시에 들어와도 한 번만 입금되고, 모두 같은 거래 번호를 받는다.
	 * 잠금 뒤에 키를 확인하기 때문에 나머지 9건은 첫 번째가 커밋되기를 기다렸다가 저장된 결과를 받는다.
	 */
	@Test
	void concurrentDepositsWithSameKeyAreAppliedOnce() throws Exception {
		Long id = openWithBalance("동시재전송", 0);
		IdempotencyKey key = newKey();
		List<Callable<IdempotentResult<LedgerTransaction>>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> accountService.deposit(id, 10_000, key));
		}

		List<IdempotentResult<LedgerTransaction>> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
			for (Future<IdempotentResult<LedgerTransaction>> future : executor.invokeAll(tasks)) {
				results.add(future.get());
			}
		}

		assertThat(results).extracting(r -> r.value().getId()).containsOnly(results.getFirst().value().getId());
		assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
		assertThat(balance(id)).isEqualTo(10_000);
		assertThat(ledgerCount(id, "DEPOSIT")).isEqualTo(1);
	}

	/** 같은 키의 이체 10건이 동시에 들어와도 한 번만 이체된다. */
	@Test
	void concurrentTransfersWithSameKeyAreAppliedOnce() throws Exception {
		Long from = openWithBalance("동시이체보냄", 100_000);
		Long to = openWithBalance("동시이체받음", 0);
		IdempotencyKey key = newKey();
		List<Callable<IdempotentResult<TransferResult>>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> transferService.transfer(from, to, 3_000, key));
		}

		try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
			for (Future<IdempotentResult<TransferResult>> future : executor.invokeAll(tasks)) {
				future.get();
			}
		}

		assertThat(balance(from)).isEqualTo(97_000);
		assertThat(balance(to)).isEqualTo(3_000);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM transfer WHERE from_account_id = ?", Long.class, from)).isEqualTo(1);
	}

	/**
	 * 같은 키로 서로 다른 계좌에 동시에 입금하는 경우. 계좌가 달라 잠금으로 줄을 세울 수 없으므로
	 * 둘 다 키 확인을 통과할 수 있지만, 유니크 제약이 하나만 남기고 나머지는 키 재사용으로 거부한다.
	 */
	@Test
	void concurrentSameKeyOnDifferentAccountsKeepsOnlyOne() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ids.add(openWithBalance("키경합" + i, 0));
		}
		IdempotencyKey key = newKey();
		List<Callable<IdempotentResult<LedgerTransaction>>> tasks = new ArrayList<>();
		for (Long id : ids) {
			tasks.add(() -> accountService.deposit(id, 10_000, key));
		}

		int succeeded = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
			for (Future<IdempotentResult<LedgerTransaction>> future : executor.invokeAll(tasks)) {
				try {
					future.get();
					succeeded++;
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(IdempotencyKeyReusedException.class);
				}
			}
		}

		assertThat(succeeded).isEqualTo(1);
		assertThat(ids.stream().mapToLong(this::balance).sum()).isEqualTo(10_000);
	}

}
