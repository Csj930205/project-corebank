package com.corebank.interest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 이자와 이자소득세 계산. DB 를 보지 않는 순수 계산이라 단위 테스트로 규칙을 확인한다.
 *
 * <ul>
 *   <li><b>세전 이자</b> = Σ(일별 마감 잔액 × 연이율%) ÷ 36,500, 원 미만 버림.
 *       36,500 = 365일 × 100(이율이 % 라서). 원화 예금은 윤년에도 365일로 나누는 것이 보통이다.</li>
 *   <li><b>이자소득세</b> = 세전 이자 × 14%, 10원 미만 버림. <b>지방소득세</b> = 이자소득세 × 10%, 10원 미만 버림.
 *       합치면 약 15.4% 다(원천징수). 세금은 은행이 고객 대신 떼어 두었다가 낸다.</li>
 * </ul>
 *
 * <p>모두 "버림"이다. 반올림하면 은행이 고객에게 약속하지 않은 돈(0.5원 이상)을 주거나, 세금을 더 걷게 된다.
 * double 을 쓰지 않는다. 0.1 같은 수를 2진수로 정확히 나타내지 못해 큰 잔액에서 1원씩 틀어진다.
 */
public final class InterestCalculator {

	/** 365일 × 100 (이율 단위 %) */
	static final BigDecimal DIVISOR = BigDecimal.valueOf(36_500);

	private InterestCalculator() {
	}

	/**
	 * @param gross    세전 이자
	 * @param incomeTax 이자소득세(14%)
	 * @param localTax  지방소득세(이자소득세의 10%)
	 */
	public record Interest(long gross, long incomeTax, long localTax) {

		public long tax() {
			return incomeTax + localTax;
		}

		/** 고객 잔액에 더해지는 세후 이자 */
		public long net() {
			return gross - tax();
		}
	}

	/** @param balanceRateSum Σ(일별 잔액 × 연이율%). 정수 × 소수 둘째 자리라 정확한 값이다 */
	public static Interest of(BigDecimal balanceRateSum) {
		long gross = balanceRateSum.divide(DIVISOR, 0, RoundingMode.DOWN).longValueExact();
		long incomeTax = floorTo10(Math.multiplyExact(gross, 14L) / 100);
		long localTax = floorTo10(incomeTax / 10);
		return new Interest(gross, incomeTax, localTax);
	}

	private static long floorTo10(long amount) {
		return amount / 10 * 10;
	}

}
