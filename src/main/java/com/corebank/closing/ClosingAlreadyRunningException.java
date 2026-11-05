package com.corebank.closing;

import java.time.LocalDate;

/** 같은 영업일 마감이 이미 실행 중일 때. ApiExceptionHandler 가 409 로 바꾼다. */
public class ClosingAlreadyRunningException extends RuntimeException {

	public ClosingAlreadyRunningException(LocalDate businessDate) {
		super("이미 실행 중인 마감이 있습니다. businessDate=" + businessDate);
	}

}
