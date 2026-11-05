package com.corebank.transfer;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountClosedException;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountService;
import com.corebank.account.BalanceLimitExceededException;
import com.corebank.account.InsufficientBalanceException;
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
 * 이체 통합 테스트. 실제 Oracle 에서 잔액, 원장, 롤백, 데드락을 검증한다. Docker 가 필요하다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferIntegrationTest {

	@Autowired
	private TransferService transferService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long openWithBalance(String name, long balance) {
		Long id = accountService.open(name).getId();
		if (balance > 0) {
			accountService.deposit(id, balance, newKey()).value();
		}
		return id;
	}

	private long balance(Long id) {
		return accountService.get(id).getBalance();
	}

	private long transferCount(Long accountId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM transfer WHERE from_account_id = ? OR to_account_id = ?",
				Long.class, accountId, accountId);
	}

	/** 이체하면 보내는 계좌는 줄고 받는 계좌는 늘며, 결과에 두 잔액이 담긴다. */
	@Test
	void transferMovesMoneyBetweenAccounts() {
		Long from = openWithBalance("보내는사람", 10_000);
		Long to = openWithBalance("받는사람", 1_000);

		TransferResult result = transferService.transfer(from, to, 3_000, newKey()).value();

		assertThat(result.fromBalanceAfter()).isEqualTo(7_000);
		assertThat(result.toBalanceAfter()).isEqualTo(4_000);
		assertThat(balance(from)).isEqualTo(7_000);
		assertThat(balance(to)).isEqualTo(4_000);
	}

	/**
	 * 원장에는 계좌별 거래 2건이 같은 이체 번호로 남고, 각 거래의 거래 후 잔액이 맞다.
	 * 분개는 고객 예금 차변(보내는 계좌) / 가계정 대변, 가계정 차변 / 고객 예금 대변(받는 계좌)이다.
	 */
	@Test
	void transferWritesTwoBalancedTransactions() {
		Long from = openWithBalance("원장보냄", 10_000);
		Long to = openWithBalance("원장받음", 0);

		Long transferId = transferService.transfer(from, to, 3_000, newKey()).value().transfer().getId();

		List<String> transactions = jdbcTemplate.queryForList("""
				SELECT transaction_type || ':' || account_id || ':' || amount || ':' || balance_after
				FROM ledger_transaction WHERE transfer_id = ? ORDER BY id
				""", String.class, transferId);
		assertThat(transactions).containsExactly(
				"TRANSFER_OUT:" + from + ":3000:7000",
				"TRANSFER_IN:" + to + ":3000:3000");

		List<String> entries = jdbcTemplate.queryForList("""
				SELECT t.transaction_type || ':' || e.ledger_account || ':' || e.entry_side || ':' || e.amount
				FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
				WHERE t.transfer_id = ? ORDER BY e.id
				""", String.class, transferId);
		assertThat(entries).containsExactly(
				"TRANSFER_OUT:CUSTOMER_DEPOSIT:DEBIT:3000",
				"TRANSFER_OUT:TRANSFER_CLEARING:CREDIT:3000",
				"TRANSFER_IN:TRANSFER_CLEARING:DEBIT:3000",
				"TRANSFER_IN:CUSTOMER_DEPOSIT:CREDIT:3000");
	}

	@Test
	void transferAllowsExactBalance() {
		Long from = openWithBalance("전액보냄", 10_000);
		Long to = openWithBalance("전액받음", 0);

		transferService.transfer(from, to, 10_000, newKey()).value();

		assertThat(balance(from)).isZero();
		assertThat(balance(to)).isEqualTo(10_000);
	}

	/** 잔액이 부족하면 두 계좌 모두 그대로이고 이체 기록도 없다. */
	@Test
	void transferRejectsInsufficientBalance() {
		Long from = openWithBalance("잔액부족", 10_000);
		Long to = openWithBalance("못받음", 0);

		assertThatThrownBy(() -> transferService.transfer(from, to, 10_001, newKey()).value())
				.isInstanceOf(InsufficientBalanceException.class);

		assertThat(balance(from)).isEqualTo(10_000);
		assertThat(balance(to)).isZero();
		assertThat(transferCount(from)).isZero();
	}

	@Test
	void transferRejectsSameAccount() {
		Long id = openWithBalance("자기자신", 10_000);

		assertThatThrownBy(() -> transferService.transfer(id, id, 1_000, newKey()).value())
				.isInstanceOf(SameAccountTransferException.class);

		assertThat(balance(id)).isEqualTo(10_000);
	}

	/** 보내는 쪽이든 받는 쪽이든 계좌가 없으면 404 예외이고 아무것도 바뀌지 않는다. */
	@Test
	void transferRejectsMissingAccounts() {
		Long id = openWithBalance("상대없음", 10_000);

		assertThatThrownBy(() -> transferService.transfer(id, -1L, 1_000, newKey()).value())
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> transferService.transfer(-1L, id, 1_000, newKey()).value())
				.isInstanceOf(AccountNotFoundException.class);

		assertThat(balance(id)).isEqualTo(10_000);
		assertThat(transferCount(id)).isZero();
	}

	/** 해지된 계좌는 보내는 쪽이든 받는 쪽이든 거부된다. */
	@Test
	void transferRejectsClosedAccounts() {
		Long active = openWithBalance("정상", 10_000);
		Long closed = openWithBalance("해지", 10_000);
		accountService.close(closed, newKey());

		assertThatThrownBy(() -> transferService.transfer(closed, active, 1_000, newKey()).value())
				.isInstanceOf(AccountClosedException.class);
		assertThatThrownBy(() -> transferService.transfer(active, closed, 1_000, newKey()).value())
				.isInstanceOf(AccountClosedException.class);

		assertThat(balance(active)).isEqualTo(10_000);
		assertThat(balance(closed)).isZero();
		assertThat(transferCount(active)).isZero();
	}

	/**
	 * 출금은 성공했는데 입금 쪽에서 실패하는 경우. 받는 계좌가 한도 끝이라 입금이 거부되면,
	 * 이미 메모리에서 차감한 보내는 계좌 잔액도 롤백되어야 한다. "반쪽 이체"가 없음을 보이는 테스트다.
	 */
	@Test
	void failedDepositSideRollsBackWithdrawalSide() {
		Long from = openWithBalance("반쪽보냄", 10_000);
		Long to = openWithBalance("한도끝", Long.MAX_VALUE);

		assertThatThrownBy(() -> transferService.transfer(from, to, 1_000, newKey()).value())
				.isInstanceOf(BalanceLimitExceededException.class);

		assertThat(balance(from)).isEqualTo(10_000);
		assertThat(balance(to)).isEqualTo(Long.MAX_VALUE);
		assertThat(transferCount(from)).isZero();
	}

	/**
	 * A→B 와 B→A 이체를 동시에 쏟아부어도 데드락 없이 모두 성공한다.
	 * 보내는 계좌부터 잠그도록 바꾸면 Oracle 이 데드락을 감지해 일부가 ORA-00060 으로 실패한다.
	 */
	@Test
	void opposingTransfersDoNotDeadlock() throws Exception {
		Long a = openWithBalance("데드락A", 100_000);
		Long b = openWithBalance("데드락B", 100_000);
		List<Callable<TransferResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> transferService.transfer(a, b, 1_000, newKey()).value());
			tasks.add(() -> transferService.transfer(b, a, 1_000, newKey()).value());
		}

		List<Throwable> failures = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
			for (Future<TransferResult> future : executor.invokeAll(tasks)) {
				try {
					future.get();
				} catch (ExecutionException e) {
					failures.add(e.getCause());
				}
			}
		}

		assertThat(failures).isEmpty();
		// 같은 금액을 같은 횟수만큼 주고받았으므로 원래대로다.
		assertThat(balance(a)).isEqualTo(100_000);
		assertThat(balance(b)).isEqualTo(100_000);
		assertThat(transferCount(a)).isEqualTo(40);
	}

	/**
	 * 한 계좌에서 여러 계좌로 동시에 이체해도 잔액을 넘겨 보내지 않는다.
	 * 10,000원에서 3,000원씩 10건이면 정확히 3건만 성공해야 한다.
	 */
	@Test
	void concurrentTransfersNeverOverdraw() throws Exception {
		Long from = openWithBalance("동시이체", 10_000);
		List<Long> receivers = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			receivers.add(openWithBalance("수취" + i, 0));
		}
		List<Callable<TransferResult>> tasks = new ArrayList<>();
		for (Long to : receivers) {
			tasks.add(() -> transferService.transfer(from, to, 3_000, newKey()).value());
		}

		int succeeded = 0;
		try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
			for (Future<TransferResult> future : executor.invokeAll(tasks)) {
				try {
					future.get();
					succeeded++;
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(InsufficientBalanceException.class);
				}
			}
		}

		assertThat(succeeded).isEqualTo(3);
		assertThat(balance(from)).isEqualTo(1_000);
		long received = receivers.stream().mapToLong(this::balance).sum();
		assertThat(received).isEqualTo(9_000);
	}

	/** 원장 전체 불변 조건: 차변 합 = 대변 합, 이체 가계정 합계 = 0, 계좌 잔액 = 고객 예금 분개 합계. */
	@Test
	void ledgerInvariantsHoldAfterTransfers() {
		Long from = openWithBalance("불변보냄", 50_000);
		Long to = openWithBalance("불변받음", 0);
		transferService.transfer(from, to, 12_345, newKey()).value();
		transferService.transfer(to, from, 2_345, newKey()).value();

		assertThat(jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'DEBIT' THEN amount ELSE -amount END), 0) FROM ledger_entry
				""", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COALESCE(SUM(CASE entry_side WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
				FROM ledger_entry WHERE ledger_account = 'TRANSFER_CLEARING'
				""", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account a
				WHERE a.balance <> COALESCE((SELECT SUM(CASE entry_side WHEN 'CREDIT' THEN amount ELSE -amount END)
				                             FROM ledger_entry e WHERE e.account_id = a.id), 0)
				""", Long.class)).isZero();
	}

}
