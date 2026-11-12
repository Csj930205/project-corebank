package com.corebank.interest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 이자 자동 실행. 매일 00:20 에 전날 적수를 쌓고, 매달 1일 00:30 에 지난달을 결산한다.
 * 결산이 적립보다 늦게 도는 것은 지난달 말일의 적수가 먼저 쌓여야 하기 때문이다.
 *
 * <p>corebank.interest.scheduler.enabled=false 로 끌 수 있다(테스트). 서버 여러 대의 중복 실행은 일 마감과 같은 한계다.
 * 다만 둘 다 다시 실행해도 안전하다(적수는 날짜별 한 번, 지급한 적수는 다시 지급하지 않는다).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "corebank.interest.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class InterestScheduler {

	private static final Logger log = LoggerFactory.getLogger(InterestScheduler.class);

	private final InterestService service;
	private final Clock clock;

	public InterestScheduler(InterestService service, Clock clock) {
		this.service = service;
		this.clock = clock;
	}

	@Scheduled(cron = "${corebank.interest.accrual-cron:0 20 0 * * *}")
	public void accrueYesterday() {
		LocalDate yesterday = LocalDate.now(clock).minusDays(1);
		try {
			log.info("이자 적립: {}", service.accrue(yesterday));
		} catch (RuntimeException e) {
			log.error("이자 적립 실패: businessDate={}", yesterday, e);
		}
	}

	@Scheduled(cron = "${corebank.interest.settlement-cron:0 30 0 1 * *}")
	public void settleLastMonth() {
		YearMonth lastMonth = YearMonth.now(clock).minusMonths(1);
		try {
			log.info("이자 결산: {}", service.settle(lastMonth));
		} catch (RuntimeException e) {
			log.error("이자 결산 실패: month={}", lastMonth, e);
		}
	}

}
