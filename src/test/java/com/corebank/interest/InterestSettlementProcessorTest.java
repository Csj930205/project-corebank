package com.corebank.interest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 결산 배치 processor 단위 테스트. DB 없이 reader 가 읽어 온 적수 요약만으로 확인한다. */
class InterestSettlementProcessorTest {

	private final InterestSettlementProcessor processor = new InterestSettlementProcessor();

	/** 100만 원 × 3.65% × 30일 = 적수 109,500,000 → 세전 3,000원, 소득세 420원, 지방세 42 → 40원, 세후 2,540원 */
	private static InterestAccruals.Unpaid thirtyDays() {
		return new InterestAccruals.Unpaid(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 30,
				new BigDecimal("109500000.00"));
	}

	@Test
	void calculatesInterestAndTax() {
		InterestSettlement result = processor.process(new SettlementTarget(7L, false, thirtyDays()));

		assertThat(result.accountId()).isEqualTo(7L);
		assertThat(result.unpaid()).isEqualTo(thirtyDays());
		assertThat(result.interest()).isEqualTo(new InterestCalculator.Interest(3_000, 420, 40));
		assertThat(result.interest().net()).isEqualTo(2_540);
	}

	/** 해지된 계좌는 null. Spring Batch 가 걸러 내(filter) writer 로 가지 않고 FILTER_COUNT 로 센다. */
	@Test
	void closedAccountIsFiltered() {
		assertThat(processor.process(new SettlementTarget(7L, true, thirtyDays()))).isNull();
	}

	/** 이자가 1원도 안 돼도 걸러 내지 않는다. 0원 지급 기록이 남아야 그 적수를 다시 결산하지 않는다. */
	@Test
	void zeroInterestIsStillSettled() {
		InterestSettlement result = processor.process(new SettlementTarget(7L, false,
				new InterestAccruals.Unpaid(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), 1, new BigDecimal("0.10"))));

		assertThat(result.interest()).isEqualTo(new InterestCalculator.Interest(0, 0, 0));
	}

}
