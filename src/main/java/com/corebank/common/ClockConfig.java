package com.corebank.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각을 빈으로 둔다. 테스트에서 바꿔 끼워 만료된 토큰(JWT)이나 다른 날짜의 마감(일 마감)을 검증할 수 있다.
 *
 * <p>서버 시각(시스템 기본 시간대)을 쓴다. 거래 시각(created_at)이 같은 기준으로 저장되므로, "오늘이 며칠인가" 도
 * 같은 기준이어야 한다. UTC 로 두면 한국 시각 00:10 에 날짜가 아직 전날이라 마감 날짜가 하루 어긋난다.
 * JWT 는 시간대와 상관없는 Instant 만 쓰므로 영향이 없다.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}

}
