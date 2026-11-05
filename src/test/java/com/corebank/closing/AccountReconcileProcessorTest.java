package com.corebank.closing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌 대사 규칙 단위 테스트. DB 없이 리더가 읽어 온 값만으로 검증한다.
 */
class AccountReconcileProcessorTest {

	private final AccountReconcileProcessor processor = new AccountReconcileProcessor();

	private static AccountSnapshot snapshot(long balance, long ledgerTotal, long ledgerAtCutoff, Long balanceAfterAtCutoff) {
		return new AccountSnapshot(1L, "1100000000001", BigDecimal.valueOf(balance), BigDecimal.valueOf(ledgerTotal),
				BigDecimal.valueOf(ledgerAtCutoff), balanceAfterAtCutoff == null ? null : BigDecimal.valueOf(balanceAfterAtCutoff));
	}

	/** 모두 맞으면 null. Spring Batch 가 걸러 내(filter) writer 로 가지 않는다. */
	@Test
	void matchingAccountIsFiltered() {
		assertThat(processor.process(snapshot(10_000, 10_000, 7_000, 7_000L))).isNull();
	}

	/** 마감 시점까지 거래가 없던 계좌는 거래 후 잔액이 없고(null), 원장 합계 0과 맞는 것으로 본다. */
	@Test
	void accountWithoutTransactionsBeforeCutoffIsFiltered() {
		assertThat(processor.process(snapshot(0, 0, 0, null))).isNull();
	}

	/** 잔액 컬럼만 바뀌면 현재 잔액 불일치. expected 는 원장, actual 은 잔액이다. */
	@Test
	void balanceDifferentFromLedgerIsReported() {
		AccountReconciliation result = processor.process(snapshot(10_001, 10_000, 10_000, 10_000L));

		assertThat(result.mismatches()).singleElement().satisfies(m -> {
			assertThat(m.type()).isEqualTo(MismatchType.BALANCE_LEDGER_MISMATCH);
			assertThat(m.expected()).isEqualByComparingTo("10000");
			assertThat(m.actual()).isEqualByComparingTo("10001");
		});
	}

	/** 마감 시점 거래 후 잔액이 원장과 다르면 시점 불일치. */
	@Test
	void snapshotDifferentFromLedgerIsReported() {
		AccountReconciliation result = processor.process(snapshot(10_000, 10_000, 7_000, 7_005L));

		assertThat(result.mismatches()).extracting(ClosingMismatch::type)
				.containsExactly(MismatchType.SNAPSHOT_MISMATCH);
	}

	/** 두 가지가 동시에 틀리면 두 건 모두 남긴다. */
	@Test
	void bothMismatchesAreReported() {
		AccountReconciliation result = processor.process(snapshot(1, 0, 0, 5L));

		assertThat(result.mismatches()).extracting(ClosingMismatch::type)
				.containsExactly(MismatchType.BALANCE_LEDGER_MISMATCH, MismatchType.SNAPSHOT_MISMATCH);
	}

}
