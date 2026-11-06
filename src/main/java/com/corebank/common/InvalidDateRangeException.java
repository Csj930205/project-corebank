package com.corebank.common;

/** 조회 기간이 DB 가 다룰 수 있는 범위(0001-01-01 ~ 9999-12-31)를 벗어남: 400 */
public class InvalidDateRangeException extends RuntimeException {

	public InvalidDateRangeException() {
		super("날짜는 0001-01-01 부터 9999-12-31 사이여야 합니다.");
	}

}
