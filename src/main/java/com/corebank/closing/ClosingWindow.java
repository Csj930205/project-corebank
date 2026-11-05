package com.corebank.closing;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 영업일의 시간 범위 [from, to). to 가 곧 마감 시점(cutoff)이다.
 *
 * <p>거래 시각(created_at)은 서버 시각으로 저장되므로, 영업일도 서버 시각의 0시 기준으로 자른다.
 */
record ClosingWindow(LocalDate businessDate) {

	LocalDateTime from() {
		return businessDate.atStartOfDay();
	}

	/** 다음 날 0시. 이 시각 "미만" 까지가 영업일이다. */
	LocalDateTime cutoff() {
		return businessDate.plusDays(1).atStartOfDay();
	}

}
