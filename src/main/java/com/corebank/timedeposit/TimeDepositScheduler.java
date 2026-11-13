package com.corebank.timedeposit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 정기예금 만기 자동 실행. 매일 00:40 에 오늘까지 만기가 된 예금을 해지해 원리금을 연결계좌로 보낸다.
 * 일 마감(00:10)·이자 적립(00:20)이 어제 장부를 정리한 뒤에 돈다. 다시 실행해도 안전하다(이미 해지한 건은 대상이 아니다).
 *
 * <p>corebank.time-deposit.scheduler.enabled=false 로 끌 수 있다(테스트).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "corebank.time-deposit.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TimeDepositScheduler {

	private static final Logger log = LoggerFactory.getLogger(TimeDepositScheduler.class);

	private final TimeDepositService service;

	public TimeDepositScheduler(TimeDepositService service) {
		this.service = service;
	}

	@Scheduled(cron = "${corebank.time-deposit.maturity-cron:0 40 0 * * *}")
	public void matureDue() {
		try {
			log.info("정기예금 만기 처리: {}", service.matureDue());
		} catch (RuntimeException e) {
			log.error("정기예금 만기 처리 실패", e);
		}
	}

}
