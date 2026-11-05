package com.corebank.account;

import com.corebank.TestcontainersConfiguration;
import com.corebank.account.TransactionHistoryResponse.Direction;
import com.corebank.account.TransactionHistoryResponse.Item;
import com.corebank.ledger.TransactionType;
import com.corebank.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

import static com.corebank.support.TestKeys.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래내역 조회 통합 테스트. 실제 Oracle 에서 정렬, 페이지 나누기, 이체 상대 정보를 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionHistoryIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferService transferService;

	/** 네 가지 거래가 최신순으로 나오고, 방향·금액·거래 후 잔액·이체 상대가 맞다. */
	@Test
	void historyListsAllTypesNewestFirst() {
		Account me = accountService.open("내역주인");
		Account friend = accountService.open("친구");
		accountService.deposit(friend.getId(), 50_000, newKey());

		accountService.deposit(me.getId(), 10_000, newKey());
		accountService.withdraw(me.getId(), 3_000, newKey());
		transferService.transfer(me.getId(), friend.getId(), 2_000, newKey());
		transferService.transfer(friend.getId(), me.getId(), 5_000, newKey());

		TransactionHistoryResponse history = accountService.history(me.getId(), null, 20);

		assertThat(history.accountId()).isEqualTo(me.getId());
		assertThat(history.nextBefore()).isNull();
		assertThat(history.transactions())
				.extracting(Item::type, Item::direction, Item::amount, Item::balanceAfter)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(TransactionType.TRANSFER_IN, Direction.IN, 5_000L, 10_000L),
						org.assertj.core.groups.Tuple.tuple(TransactionType.TRANSFER_OUT, Direction.OUT, 2_000L, 5_000L),
						org.assertj.core.groups.Tuple.tuple(TransactionType.WITHDRAWAL, Direction.OUT, 3_000L, 7_000L),
						org.assertj.core.groups.Tuple.tuple(TransactionType.DEPOSIT, Direction.IN, 10_000L, 10_000L));

		Item received = history.transactions().get(0);
		assertThat(received.transferId()).isNotNull();
		assertThat(received.counterparty().accountId()).isEqualTo(friend.getId());
		assertThat(received.counterparty().accountNumber()).isEqualTo(friend.getAccountNumber());
		assertThat(received.counterparty().ownerName()).isEqualTo("친구");
		assertThat(history.transactions().get(2).counterparty()).isNull();
		assertThat(history.transactions().get(3).transferId()).isNull();
	}

	/** 다른 계좌의 거래는 섞이지 않는다. 이체 상대 쪽 거래도 상대 계좌 내역에만 나온다. */
	@Test
	void historyContainsOnlyOwnTransactions() {
		Account me = accountService.open("내것만");
		Account other = accountService.open("남의것");
		accountService.deposit(me.getId(), 1_000, newKey());
		accountService.deposit(other.getId(), 9_000, newKey());
		transferService.transfer(other.getId(), me.getId(), 500, newKey());

		TransactionHistoryResponse history = accountService.history(me.getId(), null, 20);

		assertThat(history.transactions()).extracting(Item::type)
				.containsExactly(TransactionType.TRANSFER_IN, TransactionType.DEPOSIT);
	}

	/** size 씩 nextBefore 를 따라가면 빠짐없이, 겹치지 않게 전부 받고, 마지막 페이지의 nextBefore 는 null 이다. */
	@Test
	void pagingWalksThroughEverythingOnce() {
		Long id = accountService.open("페이지").getId();
		for (int i = 1; i <= 7; i++) {
			accountService.deposit(id, i * 100L, newKey());
		}

		List<Long> amounts = new ArrayList<>();
		List<Integer> pageSizes = new ArrayList<>();
		Long before = null;
		do {
			TransactionHistoryResponse page = accountService.history(id, before, 3);
			page.transactions().forEach(item -> amounts.add(item.amount()));
			pageSizes.add(page.transactions().size());
			before = page.nextBefore();
		} while (before != null);

		assertThat(pageSizes).containsExactly(3, 3, 1);
		assertThat(amounts).containsExactly(700L, 600L, 500L, 400L, 300L, 200L, 100L);
	}

	/** 거래가 정확히 size 의 배수면, 마지막 페이지에서 nextBefore 가 null 이라 빈 페이지를 한 번 더 요청하지 않는다. */
	@Test
	void exactMultipleOfSizeHasNoEmptyTrailingPage() {
		Long id = accountService.open("딱맞음").getId();
		for (int i = 0; i < 4; i++) {
			accountService.deposit(id, 1_000, newKey());
		}

		TransactionHistoryResponse first = accountService.history(id, null, 2);
		TransactionHistoryResponse second = accountService.history(id, first.nextBefore(), 2);

		assertThat(first.nextBefore()).isNotNull();
		assertThat(second.transactions()).hasSize(2);
		assertThat(second.nextBefore()).isNull();
	}

	/**
	 * 커서 방식을 고른 이유. 첫 페이지를 본 뒤 새 거래가 생겨도 두 번째 페이지가 밀리지 않는다.
	 * 쪽 번호(offset) 방식이었다면 새 거래 한 건만큼 밀려서, 첫 페이지 마지막 거래가 두 번째 페이지에 또 나온다.
	 */
	@Test
	void newTransactionBetweenPagesDoesNotShiftNextPage() {
		Long id = accountService.open("밀림없음").getId();
		for (int i = 1; i <= 4; i++) {
			accountService.deposit(id, i * 100L, newKey());
		}

		TransactionHistoryResponse first = accountService.history(id, null, 2);
		accountService.deposit(id, 9_999, newKey());
		TransactionHistoryResponse second = accountService.history(id, first.nextBefore(), 2);

		assertThat(first.transactions()).extracting(Item::amount).containsExactly(400L, 300L);
		assertThat(second.transactions()).extracting(Item::amount).containsExactly(200L, 100L);
	}

	@Test
	void emptyAccountHasEmptyHistory() {
		Long id = accountService.open("빈계좌").getId();

		TransactionHistoryResponse history = accountService.history(id, null, 20);

		assertThat(history.transactions()).isEmpty();
		assertThat(history.nextBefore()).isNull();
	}

	@Test
	void missingAccountThrows() {
		assertThatThrownBy(() -> accountService.history(-1L, null, 20))
				.isInstanceOf(AccountNotFoundException.class);
	}

}
