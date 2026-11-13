package com.corebank.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 테스트가 옮길 수 있는 앱 시계. 정기예금처럼 "가입 → 몇 달 뒤 해지" 를 한 테스트 안에서 보려면 시계를 중간에 옮겨야 한다.
 * 고정된 오프셋 시계(Clock.offset)로는 가입일과 해지일을 다르게 만들 수 없다.
 *
 * <p>원장·개설 시각(DbTime)은 이 시계와 상관없이 실제 시각이다. 업무 날짜(가입일, 해지일, 만기 판단)만 이 시계를 따른다.
 * 토큰 만료도 앱 시계를 쓰므로 실제 시각보다 뒤로는 옮기지 않는다(앞으로만 옮긴다).
 */
public class MutableClock extends Clock {

	private final ZoneId zone;
	private volatile Duration offset = Duration.ZERO;

	public MutableClock() {
		this(ZoneId.systemDefault());
	}

	private MutableClock(ZoneId zone) {
		this.zone = zone;
	}

	/** 실제 시각으로 되돌린다 */
	public void reset() {
		offset = Duration.ZERO;
	}

	/** 실제 오늘에서 days 일 뒤의 같은 시각으로 옮긴다 */
	public void advanceDays(long days) {
		offset = Duration.ofDays(days);
	}

	/** 실제 오늘에서 그 날짜의 같은 시각으로 옮긴다. 오늘보다 앞이면 거부한다 */
	public void moveTo(LocalDate date) {
		long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), date);
		if (days < 0) {
			throw new IllegalArgumentException("시계는 앞으로만 옮긴다: " + date);
		}
		advanceDays(days);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		MutableClock clock = new MutableClock(zone);
		clock.offset = offset;
		return clock;
	}

	@Override
	public Instant instant() {
		return Instant.now().plus(offset);
	}

}
