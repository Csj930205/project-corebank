package com.corebank.interest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 이자·세금 계산 규칙. DB 없이 빠르게 돈다. */
class InterestCalculatorTest {

	private static InterestCalculator.Interest of(String balanceRateSum) {
		return InterestCalculator.of(new BigDecimal(balanceRateSum));
	}

	/** 100만 원 × 연 3.65% × 30일 = 3,000원. 소득세 420원, 지방세 42원 → 40원, 세후 2,540원 */
	@Test
	void monthOfInterestWithTax() {
		// Σ(잔액 × 이율) = 1,000,000 × 3.65 × 30
		InterestCalculator.Interest interest = of("109500000");

		assertThat(interest.gross()).isEqualTo(3_000);
		assertThat(interest.incomeTax()).isEqualTo(420);
		assertThat(interest.localTax()).isEqualTo(40);
		assertThat(interest.net()).isEqualTo(2_540);
	}

	/** 원 미만은 버린다. 반올림하지 않는다(0.99원도 0원) */
	@Test
	void truncatesBelowOneWon() {
		assertThat(of("36499.99").gross()).isZero();
		assertThat(of("36500").gross()).isEqualTo(1);
		assertThat(of("73099.99").gross()).isEqualTo(2);
	}

	/** 세금은 10원 미만 버림. 세전 71원이면 소득세 9.94원 → 0원이라 세금이 없다 */
	@Test
	void taxTruncatesBelowTenWon() {
		InterestCalculator.Interest small = of(String.valueOf(71 * 36_500));
		assertThat(small.gross()).isEqualTo(71);
		assertThat(small.tax()).isZero();
		assertThat(small.net()).isEqualTo(71);

		// 세전 72원: 소득세 10.08원 → 10원, 지방세 1원 → 0원
		InterestCalculator.Interest tenWon = of(String.valueOf(72 * 36_500));
		assertThat(tenWon.incomeTax()).isEqualTo(10);
		assertThat(tenWon.localTax()).isZero();
	}

	/**
	 * 적수 방식이 왜 필요한가: 1만 원 × 연 2% 의 하루 이자는 0.547원이다. 날마다 원 단위로 자르면 30일 내내 0원이지만,
	 * 적수로 한 번에 계산하면 16원이다.
	 */
	@Test
	void accumulatedBalanceKeepsSmallDailyInterest() {
		long dailyTruncated = InterestCalculator.of(new BigDecimal("20000.00")).gross(); // 10,000 × 2.00 하루치
		assertThat(dailyTruncated * 30).isZero();
		assertThat(of(String.valueOf(10_000L * 2 * 30)).gross()).isEqualTo(16);
	}

}
