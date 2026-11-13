package com.corebank.timedeposit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 중도해지이율 표. 경계(정확히 1·3·6개월 되는 날)가 위 구간에 속하는지 본다. */
class EarlyTerminationRateTest {

	private static final LocalDate START = LocalDate.of(2026, 1, 31);
	private static final BigDecimal CONTRACT = new BigDecimal("3.65");

	private static BigDecimal rate(LocalDate today) {
		return EarlyTerminationRate.of(CONTRACT, START, today);
	}

	@Test
	void underOneMonthIsFixedLowRate() {
		assertThat(rate(START)).isEqualByComparingTo("0.10");
		// 1월 31일 + 1개월 = 2월 28일. 그 전날까지는 1개월 미만이다.
		assertThat(rate(LocalDate.of(2026, 2, 27))).isEqualByComparingTo("0.10");
	}

	@Test
	void underOneMonthNeverExceedsContractRate() {
		assertThat(EarlyTerminationRate.of(new BigDecimal("0.05"), START, START.plusDays(3))).isEqualByComparingTo("0.05");
	}

	@Test
	void ratioGrowsWithElapsedMonthsAndTruncates() {
		// 3.65 × 0.3 = 1.095 → 1.09 (버림)
		assertThat(rate(LocalDate.of(2026, 2, 28))).isEqualByComparingTo("1.09");
		// 3개월 되는 날(4월 30일)부터 × 0.5 = 1.825 → 1.82
		assertThat(rate(LocalDate.of(2026, 4, 29))).isEqualByComparingTo("1.09");
		assertThat(rate(LocalDate.of(2026, 4, 30))).isEqualByComparingTo("1.82");
		// 6개월(7월 31일)부터 × 0.7 = 2.555 → 2.55
		assertThat(rate(LocalDate.of(2026, 7, 30))).isEqualByComparingTo("1.82");
		assertThat(rate(LocalDate.of(2026, 7, 31))).isEqualByComparingTo("2.55");
	}

}
