package com.corebank.common;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 조회 기간(날짜, 양 끝 포함). from, to 는 각각 없어도 된다(null 이면 그쪽 끝을 열어 둔다).
 *
 * <p>DB 에는 "from 0시 이상, to 다음 날 0시 미만" 으로 넘긴다. Oracle 날짜는 9999년까지라서
 * 그 범위를 벗어난 날짜는 400 으로 거절하고, to 가 9999-12-31 이면 다음 날(10000년)을 만들 수 없으니 끝을 열어 둔다.
 * from 이 to 보다 늦으면 에러 대신 빈 결과가 된다(조건 그대로의 답).
 */
public record DateRange(LocalDate from, LocalDate to) {

	public static final LocalDate MIN = LocalDate.of(1, 1, 1);
	public static final LocalDate MAX = LocalDate.of(9999, 12, 31);

	public static final DateRange ALL = new DateRange(null, null);

	public DateRange {
		if (outOfRange(from) || outOfRange(to)) {
			throw new InvalidDateRangeException();
		}
	}

	private static boolean outOfRange(LocalDate date) {
		return date != null && (date.isBefore(MIN) || date.isAfter(MAX));
	}

	/** 이 시각 이상. from 이 없으면 null. */
	public LocalDateTime startInclusive() {
		return from == null ? null : from.atStartOfDay();
	}

	/** 이 시각 미만. to 가 없거나 마지막 날(9999-12-31)이면 null. */
	public LocalDateTime endExclusive() {
		return to == null || to.equals(MAX) ? null : to.plusDays(1).atStartOfDay();
	}

}
