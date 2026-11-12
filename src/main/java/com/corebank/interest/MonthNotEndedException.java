package com.corebank.interest;

import java.time.YearMonth;

/** 이번 달이나 미래 달을 결산하려 함. 그 달 이자가 아직 다 쌓이지 않았다. 422 */
public class MonthNotEndedException extends RuntimeException {

	public MonthNotEndedException(YearMonth month) {
		super("아직 끝나지 않은 달은 결산할 수 없습니다: " + month);
	}

}
