package com.corebank.timedeposit;

import com.corebank.interest.InterestTrigger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정기예금 이자 계산. 3.65% 면 100만 원의 하루 이자가 정확히 100원이라 기대값을 손으로 확인하기 쉽다.
 */
class TimeDepositInterestTest {

	private static final BigDecimal RATE = new BigDecimal("3.65");
	private static final LocalDate START = LocalDate.of(2026, 1, 1);
	private static final LocalDate MATURITY = LocalDate.of(2027, 1, 1);

	@Test
	void maturityPaysContractRateForWholeTerm() {
		TimeDepositInterest.Settlement s = TimeDepositInterest.settle(1_000_000, RATE, START, MATURITY, MATURITY);

		assertThat(s.trigger()).isEqualTo(InterestTrigger.MATURITY);
		assertThat(s.appliedRate()).isEqualByComparingTo("3.65");
		assertThat(s.days()).isEqualTo(365);
		// 세전 36,500. 소득세 14% = 5,110, 지방세 = 511 → 510(10원 미만 버림). 세후 30,880
		assertThat(s.interest().gross()).isEqualTo(36_500);
		assertThat(s.interest().incomeTax()).isEqualTo(5_110);
		assertThat(s.interest().localTax()).isEqualTo(510);
		assertThat(s.payout(1_000_000)).isEqualTo(1_030_880);
		// 이자 기간의 마지막 날은 만기일 전날이다(한편넣기).
		assertThat(s.period().from()).isEqualTo(START);
		assertThat(s.period().to()).isEqualTo(MATURITY.minusDays(1));
	}

	/** 만기 처리가 늦어져도 만기일까지만 약정이율이다 */
	@Test
	void lateMaturityStopsAtMaturityDate() {
		TimeDepositInterest.Settlement s =
				TimeDepositInterest.settle(1_000_000, RATE, START, MATURITY, MATURITY.plusDays(3));

		assertThat(s.trigger()).isEqualTo(InterestTrigger.MATURITY);
		assertThat(s.days()).isEqualTo(365);
	}

	@Test
	void earlyTerminationUsesLowerRateAndElapsedDays() {
		LocalDate today = START.plusDays(45);
		TimeDepositInterest.Settlement s = TimeDepositInterest.settle(1_000_000, RATE, START, MATURITY, today);

		assertThat(s.trigger()).isEqualTo(InterestTrigger.EARLY_TERM);
		assertThat(s.appliedRate()).isEqualByComparingTo("1.09");
		assertThat(s.days()).isEqualTo(45);
		// 1,000,000 × 1.09 × 45 / 36,500 = 1,343.8… → 1,343
		assertThat(s.interest().gross()).isEqualTo(1_343);
	}

	@Test
	void sameDayTerminationHasNoInterest() {
		TimeDepositInterest.Settlement s = TimeDepositInterest.settle(1_000_000, RATE, START, MATURITY, START);

		assertThat(s.days()).isZero();
		assertThat(s.period()).isNull();
		assertThat(s.payout(1_000_000)).isEqualTo(1_000_000);
	}

}
