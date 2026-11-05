package com.corebank.closing;

/**
 * 마감 실행 상태. DB 의 ck_daily_closing_status 제약과 값이 일치해야 한다.
 */
public enum ClosingStatus {
	/** 실행 중. 같은 영업일은 동시에 하나만 RUNNING 일 수 있다. */
	RUNNING,
	/** 끝났고 불일치가 없다. */
	BALANCED,
	/** 끝났고 불일치가 하나 이상 있다. 배치는 성공했지만 돈이 맞지 않는다는 뜻이다. */
	UNBALANCED,
	/** 배치 자체가 실패했다(DB 오류, 서버 중단 등). 대사 결과를 믿을 수 없으므로 다시 실행해야 한다. */
	FAILED
}
