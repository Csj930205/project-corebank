package com.corebank.closing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 마감 실행 한 건(daily_closing). 실행이 끝나기 전에는 집계 값들이 null 이다.
 *
 * @param depositLiability 마감 시점 고객 예금 잔액 합계(원장 기준)
 */
public record DailyClosing(
		Long id,
		LocalDate businessDate,
		ClosingStatus status,
		ClosingTrigger trigger,
		String requestedBy,
		Long jobExecutionId,
		Long accountCount,
		BigDecimal depositLiability,
		Long mismatchCount,
		LocalDateTime startedAt,
		LocalDateTime finishedAt,
		String errorMessage) {
}
