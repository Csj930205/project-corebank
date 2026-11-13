package com.corebank.timedeposit;

import com.corebank.interest.InterestAccruals;
import com.corebank.interest.InterestCalculator;
import com.corebank.interest.InterestTrigger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 정기예금 이자 계산. DB 를 보지 않는 순수 계산이다.
 *
 * <p>원금이 기간 내내 같으므로 적수를 매일 쌓지 않고 한 번에 구한다: 적수 = 원금 × 이율 × 일수.
 * 그 뒤는 입출금 이자와 같다(세전 = 적수 ÷ 36,500 버림, 세금 14% + 1.4% 각각 10원 미만 버림, InterestCalculator).
 *
 * <p>일수는 가입일부터 해지일 전날까지다(한편넣기: 가입일은 넣고 끝나는 날은 뺀다). 오늘 가입해 오늘 해지하면 0일이라 이자가 없다.
 * 만기가 지나 늦게 해지해도(만기 처리가 하루 밀림) 만기일까지만 약정이율로 준다. 만기 후 이율은 아직 없다(README 한계).
 */
public final class TimeDepositInterest {

	private TimeDepositInterest() {
	}

	/**
	 * 해지 계산 결과.
	 *
	 * @param trigger     만기(MATURITY) 또는 중도해지(EARLY_TERM)
	 * @param appliedRate 실제로 쓴 이율. 만기면 약정이율
	 * @param period      이자 기간과 적수. 0일이면 null 이고 이자 지급 기록도 남기지 않는다
	 * @param interest    세전·세금. 0일이면 모두 0
	 */
	public record Settlement(InterestTrigger trigger, BigDecimal appliedRate, LocalDate endDate,
			InterestAccruals.Unpaid period, InterestCalculator.Interest interest) {

		public int days() {
			return period == null ? 0 : period.days();
		}

		/** 연결계좌로 갈 금액(원금 + 세후 이자) */
		public long payout(long principal) {
			return Math.addExact(principal, interest.net());
		}
	}

	/** 오늘 해지하면 어떻게 되는지. 오늘이 만기일 이후면 만기 해지다. */
	public static Settlement settle(long principal, BigDecimal contractRate, LocalDate startDate, LocalDate maturityDate,
			LocalDate today) {
		if (!today.isBefore(maturityDate)) {
			return calculate(InterestTrigger.MATURITY, principal, contractRate, startDate, maturityDate);
		}
		return calculate(InterestTrigger.EARLY_TERM, principal, EarlyTerminationRate.of(contractRate, startDate, today),
				startDate, today);
	}

	/** 만기까지 두면 받을 이자(가입 화면의 예상 이자) */
	public static Settlement atMaturity(long principal, BigDecimal contractRate, LocalDate startDate,
			LocalDate maturityDate) {
		return calculate(InterestTrigger.MATURITY, principal, contractRate, startDate, maturityDate);
	}

	private static Settlement calculate(InterestTrigger trigger, long principal, BigDecimal rate, LocalDate startDate,
			LocalDate endDate) {
		int days = Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(startDate, endDate)));
		if (days == 0) {
			return new Settlement(trigger, rate, endDate, null, new InterestCalculator.Interest(0, 0, 0));
		}
		// 원금(정수) × 이율(소수 둘째 자리) × 일수(정수): 나눗셈 전까지 오차가 없다.
		BigDecimal balanceRateSum = BigDecimal.valueOf(principal).multiply(rate).multiply(BigDecimal.valueOf(days));
		InterestAccruals.Unpaid period = new InterestAccruals.Unpaid(startDate, endDate.minusDays(1), days, balanceRateSum);
		return new Settlement(trigger, rate, endDate, period, InterestCalculator.of(balanceRateSum));
	}

}
