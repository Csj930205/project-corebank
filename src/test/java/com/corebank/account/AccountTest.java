package com.corebank.account;

import com.corebank.product.DepositProduct;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Account 업무 규칙 단위 테스트. DB 없이 객체만으로 검증한다.
 */
class AccountTest {

	private static final DepositProduct PRODUCT = DepositProduct.of("기본 입출금 통장", new java.math.BigDecimal("0.10"));

	private final Account account = Account.open("1100000000001", "홍길동", PRODUCT);

	@Test
	void depositIncreasesBalance() {
		account.deposit(1000);
		account.deposit(500);

		assertThat(account.getBalance()).isEqualTo(1500);
	}

	@Test
	void depositRejectsZeroOrNegativeAmount() {
		assertThatThrownBy(() -> account.deposit(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.deposit(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(account.getBalance()).isZero();
	}

	@Test
	void depositRejectsClosedAccount() {
		ReflectionTestUtils.setField(account, "status", AccountStatus.CLOSED);

		assertThatThrownBy(() -> account.deposit(1000)).isInstanceOf(AccountClosedException.class);
		assertThat(account.getBalance()).isZero();
	}

	/** long 범위를 넘어도 잔액이 음수로 바뀌지 않는다. */
	@Test
	void depositRejectsOverflowAndKeepsBalance() {
		account.deposit(Long.MAX_VALUE);

		assertThatThrownBy(() -> account.deposit(1)).isInstanceOf(BalanceLimitExceededException.class);
		assertThat(account.getBalance()).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void withdrawDecreasesBalance() {
		account.deposit(10_000);

		account.withdraw(3_000);

		assertThat(account.getBalance()).isEqualTo(7_000);
	}

	/** 잔액과 정확히 같은 금액은 출금할 수 있고, 잔액은 0이 된다. */
	@Test
	void withdrawAllowsExactBalance() {
		account.deposit(10_000);

		account.withdraw(10_000);

		assertThat(account.getBalance()).isZero();
	}

	/** 잔액보다 1원이라도 많으면 거부하고 잔액은 그대로다. */
	@Test
	void withdrawRejectsInsufficientBalance() {
		account.deposit(10_000);

		assertThatThrownBy(() -> account.withdraw(10_001)).isInstanceOf(InsufficientBalanceException.class);
		assertThat(account.getBalance()).isEqualTo(10_000);
	}

	@Test
	void withdrawRejectsZeroOrNegativeAmount() {
		account.deposit(10_000);

		assertThatThrownBy(() -> account.withdraw(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> account.withdraw(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThat(account.getBalance()).isEqualTo(10_000);
	}

	@Test
	void withdrawRejectsClosedAccount() {
		account.deposit(10_000);
		ReflectionTestUtils.setField(account, "status", AccountStatus.CLOSED);

		assertThatThrownBy(() -> account.withdraw(1000)).isInstanceOf(AccountClosedException.class);
		assertThat(account.getBalance()).isEqualTo(10_000);
	}

	/** 해지하면 남은 잔액이 지급액으로 나오고, 잔액 0, CLOSED, 해지 시각이 남는다. */
	@Test
	void closeReturnsRemainingBalanceAsPayout() {
		account.deposit(10_000);

		long payout = account.close();

		assertThat(payout).isEqualTo(10_000);
		assertThat(account.getBalance()).isZero();
		assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
		assertThat(account.getClosedAt()).isNotNull();
	}

	@Test
	void closeWithZeroBalancePaysNothing() {
		assertThat(account.close()).isZero();
		assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
	}

	/** 이미 해지한 계좌는 다시 해지할 수 없고, 해지 시각도 바뀌지 않는다. */
	@Test
	void closeRejectsClosedAccount() {
		account.close();
		var closedAt = account.getClosedAt();

		assertThatThrownBy(account::close).isInstanceOf(AccountClosedException.class);
		assertThat(account.getClosedAt()).isEqualTo(closedAt);
	}

	/** 해지한 뒤에는 입금도 출금도 거부된다. */
	@Test
	void closedAccountRejectsDepositAndWithdrawal() {
		account.close();

		assertThatThrownBy(() -> account.deposit(1000)).isInstanceOf(AccountClosedException.class);
		assertThatThrownBy(() -> account.withdraw(1000)).isInstanceOf(AccountClosedException.class);
	}

	// ───────────────────────── 같은 계좌인지(equals) ─────────────────────────

	/** id 가 같으면 잔액이 달라도 같은 계좌다. 해시도 변하지 않아 Set 에서 찾을 수 있다. */
	@Test
	void sameIdIsSameAccountEvenAfterBalanceChanges() {
		Account first = withId(Account.open("1100000000001", "홍길동", PRODUCT), 1L);
		Account loadedAgain = withId(Account.open("1100000000001", "홍길동", PRODUCT), 1L);
		Set<Account> set = new HashSet<>(List.of(first));

		first.deposit(1000);

		assertThat(first).isEqualTo(loadedAgain).hasSameHashCodeAs(loadedAgain);
		assertThat(set).contains(first);
	}

	/** id 가 다르면 이름과 잔액이 같아도 다른 계좌다. */
	@Test
	void differentIdIsDifferentAccount() {
		assertThat(withId(Account.open("1100000000001", "홍길동", PRODUCT), 1L))
				.isNotEqualTo(withId(Account.open("1100000000001", "홍길동", PRODUCT), 2L));
	}

	/** 저장 전(id 없음) 계좌는 자기 자신하고만 같다. 저장해 id 가 생겨도 해시가 그대로라 Set 에서 찾을 수 있다. */
	@Test
	void unsavedAccountEqualsOnlyItself() {
		Account unsaved = Account.open("1100000000001", "홍길동", PRODUCT);
		Account otherUnsaved = Account.open("1100000000001", "홍길동", PRODUCT);
		Set<Account> set = new HashSet<>(List.of(unsaved));

		assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo(otherUnsaved);

		ReflectionTestUtils.setField(unsaved, "id", 7L);
		assertThat(set).contains(unsaved);
	}

	private static Account withId(Account account, long id) {
		ReflectionTestUtils.setField(account, "id", id);
		return account;
	}

}
