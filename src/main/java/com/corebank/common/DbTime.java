package com.corebank.common;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * DB 에 저장할 현재 시각. 컬럼(TIMESTAMP(6))과 같은 마이크로초 단위로 자른다.
 *
 * <p>Linux 의 시계는 나노초까지 주는데, Oracle 은 마이크로초로 반올림해 저장한다. 자르지 않으면 처음 응답에 나간 시각
 * (메모리의 값)과 나중에 조회한 시각(DB 의 값)이 1µs 어긋난다. 멱등성 재전송이 "처음과 같은 응답" 을 돌려주지 못하게 된다.
 * macOS 는 시계가 마이크로초 단위라 로컬에서는 드러나지 않고 CI(Linux)에서 드러났다.
 */
public final class DbTime {

	private DbTime() {
	}

	public static LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

}
