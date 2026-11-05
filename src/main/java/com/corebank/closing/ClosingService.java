package com.corebank.closing;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 일 마감 실행과 결과 조회.
 *
 * <p>run 은 트랜잭션 없이 실행한다. 배치는 단계마다 스스로 트랜잭션을 열고 커밋하며, 이미 열린 트랜잭션 안에서
 * 배치를 시작하면 Spring Batch 가 거부한다(spring.batch.jdbc.validate-transaction-state).
 */
@Service
public class ClosingService {

	/** RUNNING 이 이보다 오래되면 서버가 중단된 실행으로 보고 FAILED 로 정리한다. 정상 마감은 몇 분 안에 끝난다. */
	static final Duration STALE_AFTER = Duration.ofHours(1);

	/** 상세 조회 때 보여 줄 불일치 최대 건수 */
	static final int MISMATCH_LIMIT = 200;

	private final ClosingRepository repository;
	private final JobOperator jobOperator;
	private final Job dailyClosingJob;
	private final Clock clock;

	public ClosingService(ClosingRepository repository, JobOperator jobOperator, Job dailyClosingJob, Clock clock) {
		this.repository = repository;
		this.jobOperator = jobOperator;
		this.dailyClosingJob = dailyClosingJob;
		this.clock = clock;
	}

	/**
	 * 영업일을 마감한다. 배치가 끝날 때까지 기다렸다가(동기 실행) 결과를 돌려준다.
	 *
	 * <p>오늘과 미래는 거부한다. 그날 거래가 아직 더 생길 수 있어 대사 결과가 확정되지 않기 때문이다.
	 * 같은 날을 다시 마감하는 것은 허용한다. 불일치를 고친 뒤 다시 확인하는 것이 마감 업무의 일부다.
	 *
	 * @param requestedBy 수동 실행한 관리자. 자동 실행이면 null
	 * @throws BusinessDateNotEndedException  오늘이나 미래 날짜
	 * @throws ClosingAlreadyRunningException 같은 날짜가 이미 실행 중
	 */
	public DailyClosing run(LocalDate businessDate, ClosingTrigger trigger, String requestedBy) {
		if (!businessDate.isBefore(LocalDate.now(clock))) {
			throw new BusinessDateNotEndedException(businessDate);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		repository.failStale(now.minus(STALE_AFTER), now);
		long closingId = repository.createRunning(businessDate, trigger, requestedBy, now);

		JobParameters parameters = new JobParametersBuilder()
				.addLong(DailyClosingJobConfig.CLOSING_ID, closingId)
				.addLocalDate(DailyClosingJobConfig.BUSINESS_DATE, businessDate)
				.toJobParameters();
		try {
			// 단계 안에서 난 오류는 예외로 나오지 않고 실행 상태(FAILED)로 남는다. 리스너가 daily_closing 에 기록한다.
			jobOperator.start(dailyClosingJob, parameters);
		} catch (Exception e) {
			// 잡이 시작조차 못한 경우(파라미터 오류 등). 리스너가 불리지 않으므로 여기서 FAILED 로 남긴다.
			repository.markFailed(closingId, "배치를 시작하지 못했습니다: " + e.getMessage(), LocalDateTime.now(clock));
		}
		return get(closingId);
	}

	public DailyClosing get(long closingId) {
		return repository.findById(closingId).orElseThrow(() -> new ClosingNotFoundException(closingId));
	}

	public List<DailyClosing> list(int page, int size) {
		return repository.findPage(page, size);
	}

	public long count() {
		return repository.count();
	}

	public List<DailyClosingTotal> totals(long closingId) {
		return repository.findTotals(closingId);
	}

	public List<ClosingMismatch> mismatches(long closingId) {
		return repository.findMismatches(closingId, MISMATCH_LIMIT);
	}

}
