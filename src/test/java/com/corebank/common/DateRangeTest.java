package com.corebank.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

	@Test
	void endIsStartOfNextDay() {
		var range = new DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5));

		assertThat(range.startInclusive()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
		assertThat(range.endExclusive()).isEqualTo(LocalDateTime.of(2026, 11, 6, 0, 0));
	}

	@Test
	void missingEndsAreOpen() {
		assertThat(DateRange.ALL.startInclusive()).isNull();
		assertThat(DateRange.ALL.endExclusive()).isNull();
	}

	/** 9999-12-31 의 다음 날은 Oracle 이 못 다루므로 끝을 열어 둔다. */
	@Test
	void lastDateLeavesEndOpen() {
		assertThat(new DateRange(null, DateRange.MAX).endExclusive()).isNull();
		assertThat(new DateRange(DateRange.MIN, null).startInclusive()).isEqualTo(LocalDateTime.of(1, 1, 1, 0, 0));
	}

	@Test
	void rejectsDatesOracleCannotStore() {
		assertThatThrownBy(() -> new DateRange(LocalDate.of(0, 12, 31), null)).isInstanceOf(InvalidDateRangeException.class);
		assertThatThrownBy(() -> new DateRange(null, LocalDate.of(10000, 1, 1))).isInstanceOf(InvalidDateRangeException.class);
	}

}
