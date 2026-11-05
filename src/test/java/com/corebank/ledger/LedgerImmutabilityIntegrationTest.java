package com.corebank.ledger;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountService;
import com.corebank.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장 불변성(V12). 원장 거래·분개·이체 기록은 앱 계정의 SQL 로도 고치거나 지울 수 없고, 추가(정상 거래)는 그대로 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerImmutabilityIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 입금 원장: 금액도 거래 후 잔액도 못 바꾸고, 지우지도 못한다. 값은 그대로다. */
	@Test
	void ledgerTransactionCannotBeChangedOrDeleted() {
		Long accountId = accountService.open("원장불변").getId();
		Long txId = accountService.deposit(accountId, 10_000, newKey()).value().getId();

		assertRejected("UPDATE ledger_transaction SET amount = 1 WHERE id = ?", txId);
		assertRejected("UPDATE ledger_transaction SET balance_after = 0 WHERE id = ?", txId);
		assertRejected("DELETE FROM ledger_transaction WHERE id = ?", txId);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT amount FROM ledger_transaction WHERE id = ?", Long.class, txId)).isEqualTo(10_000);
	}

	/** 분개: 한쪽만 바꿔 차대를 틀어 놓을 수 없다. */
	@Test
	void ledgerEntryCannotBeChangedOrDeleted() {
		Long accountId = accountService.open("분개불변").getId();
		Long txId = accountService.deposit(accountId, 10_000, newKey()).value().getId();

		assertRejected("UPDATE ledger_entry SET amount = amount + 1 WHERE transaction_id = ?", txId);
		assertRejected("DELETE FROM ledger_entry WHERE transaction_id = ?", txId);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_entry WHERE transaction_id = ?", Long.class, txId)).isEqualTo(2);
	}

	/** 이체 기록: 받는 계좌를 바꿔치기하거나 이체 자체를 지울 수 없다. */
	@Test
	void transferCannotBeChangedOrDeleted() {
		Long from = accountService.open("이체불변A").getId();
		Long to = accountService.open("이체불변B").getId();
		Long other = accountService.open("이체불변C").getId();
		accountService.deposit(from, 10_000, newKey());
		Long transferId = transferService.transfer(from, to, 3_000, newKey()).value().transfer().getId();

		assertRejected("UPDATE transfer SET to_account_id = ? WHERE id = ?", other, transferId);
		assertRejected("DELETE FROM transfer WHERE id = ?", transferId);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT to_account_id FROM transfer WHERE id = ?", Long.class, transferId)).isEqualTo(to);
	}

	/**
	 * 트리거가 켜져 있다. 일 마감 테스트는 원장을 조작할 때 트리거를 잠시 끄므로(LedgerTampering), 되돌리지 못하면
	 * 원장이 무방비가 된다. 어느 테스트 뒤에 돌아도 켜져 있어야 한다.
	 */
	@Test
	void appendOnlyTriggersAreEnabled() {
		List<String> statuses = jdbcTemplate.queryForList("""
				SELECT status FROM user_triggers
				WHERE trigger_name IN ('TRG_LEDGER_TRANSACTION_APPEND_ONLY', 'TRG_LEDGER_ENTRY_APPEND_ONLY',
				                       'TRG_TRANSFER_APPEND_ONLY', 'TRG_AUDIT_LOG_APPEND_ONLY')
				""", String.class);

		assertThat(statuses).hasSize(4).containsOnly("ENABLED");
	}

	private void assertRejected(String sql, Object... args) {
		assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ORA-20002");
	}

}
