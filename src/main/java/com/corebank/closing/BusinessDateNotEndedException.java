package com.corebank.closing;

import java.time.LocalDate;

/** 아직 끝나지 않은 날(오늘, 미래)을 마감하려 할 때. 그날 거래가 더 생길 수 있어 거부한다. 422 */
public class BusinessDateNotEndedException extends RuntimeException {

	public BusinessDateNotEndedException(LocalDate businessDate) {
		super("아직 끝나지 않은 영업일은 마감할 수 없습니다. businessDate=" + businessDate);
	}

}
