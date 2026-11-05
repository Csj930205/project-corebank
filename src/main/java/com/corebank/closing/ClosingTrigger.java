package com.corebank.closing;

/**
 * 마감을 시작한 주체. DB 의 ck_daily_closing_trigger 제약과 값이 일치해야 한다.
 */
public enum ClosingTrigger {
	/** 매일 정해진 시각에 전날을 자동 마감 */
	SCHEDULED,
	/** 관리자가 화면이나 API 로 실행 */
	MANUAL
}
