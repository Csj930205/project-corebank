package com.corebank.closing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 매일 정해진 시각(기본 00:10)에 전날을 자동 마감한다. 0시 정각이 아니라 10분 뒤인 이유는, 자정 직전에 시작된 거래가
 * 커밋될 시간을 두기 위해서다. 거래 시각(created_at)은 커밋 시각이 아니라 처리 시작 시각이다.
 *
 * <p>corebank.closing.scheduler.enabled=false 로 끌 수 있다(테스트). 서버를 여러 대 띄우면 각자 실행하므로,
 * 그때는 ShedLock 같은 분산 잠금이 필요하다. 지금은 같은 날짜가 동시에 돌면 RUNNING 유니크 인덱스가 하나만 통과시킨다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "corebank.closing.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ClosingScheduler {

	private static final Logger log = LoggerFactory.getLogger(ClosingScheduler.class);

	private final ClosingService closingService;
	private final Clock clock;

	public ClosingScheduler(ClosingService closingService, Clock clock) {
		this.closingService = closingService;
		this.clock = clock;
	}

	@Scheduled(cron = "${corebank.closing.cron:0 10 0 * * *}")
	public void closeYesterday() {
		LocalDate yesterday = LocalDate.now(clock).minusDays(1);
		try {
			DailyClosing result = closingService.run(yesterday, ClosingTrigger.SCHEDULED, null);
			log.info("일 마감 완료: businessDate={}, status={}, mismatches={}",
					yesterday, result.status(), result.mismatchCount());
		} catch (ClosingAlreadyRunningException e) {
			log.warn("일 마감 건너뜀(이미 실행 중): businessDate={}", yesterday);
		}
	}

}
