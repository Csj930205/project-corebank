package com.corebank.transfer;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.AccountService;
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
 * 원장 분개 저장이 실패하면 두 계좌 잔액, 이체 기록, 원장 거래가 모두 취소되는지 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferRollbackIntegrationTest {

	@Autowired
	private TransferService transferService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private LedgerEntryRepository entryRepository;

	@Test
	void everythingRollsBackWhenEntriesFail() {
		Long from = accountService.open("롤백보냄").getId();
		Long to = accountService.open("롤백받음").getId();
		accountService.deposit(from, 10_000, newKey()).value();
		doThrow(new IllegalStateException("분개 저장 실패")).when(entryRepository).saveAll(anyList());

		assertThatThrownBy(() -> transferService.transfer(from, to, 3_000, newKey()).value())
				.isInstanceOf(IllegalStateException.class);

		assertThat(accountService.get(from).getBalance()).isEqualTo(10_000);
		assertThat(accountService.get(to).getBalance()).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM transfer WHERE from_account_id = ?", Long.class, from)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_transaction WHERE account_id IN (?, ?) AND transfer_id IS NOT NULL",
				Long.class, from, to)).isZero();
	}

}
