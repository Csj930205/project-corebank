package com.corebank.account;

import com.corebank.TestcontainersConfiguration;
import com.corebank.ledger.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * 원장 기록이 실패하면 잔액 변경도 함께 취소되는지 검증한다. 입금 트랜잭션의 핵심 보장이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DepositRollbackIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private LedgerEntryRepository entryRepository;

	@Test
	void balanceAndTransactionRollBackWhenEntriesFail() {
		Long id = accountService.open("롤백").getId();
		doThrow(new IllegalStateException("분개 저장 실패")).when(entryRepository).saveAll(anyList());

		assertThatThrownBy(() -> accountService.deposit(id, 1000, newKey()).value())
				.isInstanceOf(IllegalStateException.class);

		assertThat(accountService.get(id).getBalance()).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ?", Long.class, id)).isZero();
	}

	/** 출금도 같다. 분개 저장이 실패하면 차감한 잔액이 되돌아간다. */
	@Test
	void withdrawalRollsBackWhenEntriesFail() {
		Long id = accountService.open("출금롤백").getId();
		accountService.deposit(id, 10_000, newKey()).value();
		doThrow(new IllegalStateException("분개 저장 실패")).when(entryRepository).saveAll(anyList());

		assertThatThrownBy(() -> accountService.withdraw(id, 3_000, newKey()).value())
				.isInstanceOf(IllegalStateException.class);

		assertThat(accountService.get(id).getBalance()).isEqualTo(10_000);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id = ? AND transaction_type = 'WITHDRAWAL'",
				Long.class, id)).isZero();
	}

}
