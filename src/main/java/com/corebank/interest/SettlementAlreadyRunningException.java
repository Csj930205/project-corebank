package com.corebank.interest;

import java.time.YearMonth;

/** 같은 달 이자 결산 배치가 이미 실행 중. 409 */
public class SettlementAlreadyRunningException extends RuntimeException {

	public SettlementAlreadyRunningException(YearMonth month) {
		super("같은 달 이자 결산이 이미 실행 중입니다. 끝난 뒤 다시 실행하면 남은 계좌만 지급합니다: " + month);
	}

}
