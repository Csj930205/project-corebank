package com.corebank.interest;

import java.time.LocalDate;

/** 같은 날 적수를 다른 곳에서 동시에 쌓고 있다. 409 */
public class AccrualAlreadyRunningException extends RuntimeException {

	public AccrualAlreadyRunningException(LocalDate date) {
		super("같은 날의 이자 적립이 동시에 실행됐습니다. 잠시 뒤 다시 실행하면 남은 계좌만 쌓습니다: " + date);
	}

}
