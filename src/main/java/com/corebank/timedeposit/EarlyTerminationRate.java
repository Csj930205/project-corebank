package com.corebank.timedeposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 중도해지이율. 만기 전에 해지하면 약정이율보다 낮은 이율을 준다. 은행이 약속한 기간만큼 돈을 쓸 수 없게 되기 때문이다.
 *
 * <p>실제 은행은 상품마다 표가 다르다. 여기서는 흔한 모양(경과 기간이 길수록 약정이율에 가까워짐)을 하나 정해 코드로 둔다.
 * <pre>
 * 경과 1개월 미만        연 0.10% (약정이율이 더 낮으면 약정이율)
 * 1개월 이상 3개월 미만  약정이율 × 30%
 * 3개월 이상 6개월 미만  약정이율 × 50%
 * 6개월 이상             약정이율 × 70%
 * </pre>
 * 곱한 결과는 소수 둘째 자리 밑을 버린다(이율 열이 NUMBER(5,2)이고, 이자 계산과 같이 고객에게 약속하지 않은 이자를 주지 않는다).
 * 경과 기간은 개월 수로 센다(가입일 1월 31일이면 1개월 뒤는 2월 28일 또는 29일, LocalDate.plusMonths 와 같다).
 */
public final class EarlyTerminationRate {

	static final BigDecimal UNDER_ONE_MONTH = new BigDecimal("0.10");

	private EarlyTerminationRate() {
	}

	/**
	 * @param contractRate 약정이율(%)
	 * @param startDate    가입일
	 * @param today        해지일. 만기일 전이어야 한다(만기 뒤 해지는 만기 해지다)
	 */
	public static BigDecimal of(BigDecimal contractRate, LocalDate startDate, LocalDate today) {
		if (today.isBefore(startDate.plusMonths(1))) {
			return contractRate.min(UNDER_ONE_MONTH);
		}
		String ratio;
		if (today.isBefore(startDate.plusMonths(3))) {
			ratio = "0.3";
		} else if (today.isBefore(startDate.plusMonths(6))) {
			ratio = "0.5";
		} else {
			ratio = "0.7";
		}
		return contractRate.multiply(new BigDecimal(ratio)).setScale(2, RoundingMode.DOWN);
	}

}
