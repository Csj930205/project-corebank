package com.corebank.interest;

import com.corebank.account.Account;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountRepository;
import com.corebank.closing.BusinessDateNotEndedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 이자: 매일 적수를 쌓고(accrue), 매달 지급한다(settle, Spring Batch). 해지할 때는 그때까지의 이자를 함께 준다(payOnClosure).
 *
 * <p>적립은 배치로 만들지 않았다. INSERT … SELECT 한 문장이 DB 안에서 모든 계좌를 처리해, 계좌를 앱으로 읽어 올
 * 일이 없다. 결산은 계좌마다 잠그고 잔액·원장·지급 기록을 써야 해서 앱이 계좌를 하나씩 다뤄야 하고, 그래서 배치다.
 */
@Service
public class InterestService {

	private static final Logger log = LoggerFactory.getLogger(InterestService.class);

	/** STARTED 가 이보다 오래되면 서버가 중단된 실행으로 본다. 정상 결산은 몇 분 안에 끝난다. */
	static final Duration STALE_AFTER = Duration.ofHours(1);

	private final InterestAccruals accruals;
	private final InterestPaymentRepository payments;
	private final InterestPayer payer;
	private final AccountRepository accountRepository;
	private final JobOperator jobOperator;
	private final JobRepository jobRepository;
	private final Job interestSettlementJob;
	private final Clock clock;

	public InterestService(InterestAccruals accruals, InterestPaymentRepository payments, InterestPayer payer,
			AccountRepository accountRepository, JobOperator jobOperator, JobRepository jobRepository,
			Job interestSettlementJob, Clock clock) {
		this.accruals = accruals;
		this.payments = payments;
		this.payer = payer;
		this.accountRepository = accountRepository;
		this.jobOperator = jobOperator;
		this.jobRepository = jobRepository;
		this.interestSettlementJob = interestSettlementJob;
		this.clock = clock;
	}

	/** @param accrued 새로 쌓은 계좌 수. 이미 쌓인 날을 다시 실행하면 0 */
	public record AccrualResult(LocalDate businessDate, int accrued) {
	}

	/**
	 * @param jobExecutionId 결산 배치 실행 번호(BATCH_JOB_EXECUTION)
	 * @param status         배치 결과. COMPLETED 면 끝까지 돌았다(실패 계좌가 있어도). FAILED 면 중간에 멈췄다
	 * @param paid           이 실행이 이자를 지급한 계좌 수(0원 포함)
	 * @param skipped        해지돼서 건너뛴 계좌 수
	 * @param failed         오류로 지급하지 못한 계좌 수. 다른 계좌는 계속 처리하고, 같은 달을 다시 실행하면 이 계좌만 다시 시도한다
	 */
	public record SettlementResult(YearMonth month, Long jobExecutionId, String status, int paid, int skipped,
			int failed, long totalGross, long totalTax, long totalNet) {
	}

	/**
	 * 영업일 하루치 적수를 쌓는다. 오늘과 미래는 아직 끝나지 않아 거부한다(일 마감과 같은 규칙).
	 * 같은 날을 다시 실행해도 된다. 이미 쌓인 계좌는 건너뛴다.
	 */
	@Transactional
	public AccrualResult accrue(LocalDate businessDate) {
		if (!businessDate.isBefore(LocalDate.now(clock))) {
			throw new BusinessDateNotEndedException(businessDate);
		}
		// 그날이 끝나는 시각(다음 날 00:00). 일 마감의 마감 시점과 같다.
		LocalDateTime cutoff = businessDate.plusDays(1).atStartOfDay();
		try {
			return new AccrualResult(businessDate, accruals.accrue(businessDate, cutoff, LocalDateTime.now(clock)));
		} catch (DuplicateKeyException e) {
			// 같은 날을 두 곳에서 동시에 실행해 한쪽이 먼저 쌓았다. 그쪽이 쌓았으니 이쪽은 할 일이 없다.
			throw new AccrualAlreadyRunningException(businessDate);
		}
	}

	/**
	 * 월 결산. 그 달 말일까지 쌓였지만 아직 지급하지 않은 적수로 계좌마다 이자를 지급한다.
	 * 실제 지급은 Spring Batch 잡(InterestSettlementJobConfig)이 하고, 여기서는 잡을 실행하고 결과를 모은다.
	 * 잡이 끝날 때까지 기다린다(동기 실행, 일 마감과 같다).
	 *
	 * <p>트랜잭션 없이 실행한다. 배치는 chunk 마다 스스로 트랜잭션을 열고 커밋하며, 이미 열린 트랜잭션 안에서 배치를
	 * 시작하면 Spring Batch 가 거부한다.
	 *
	 * @throws MonthNotEndedException            이번 달이나 미래 달
	 * @throws SettlementAlreadyRunningException 같은 달 결산이 실행 중
	 */
	public SettlementResult settle(YearMonth month) {
		if (!month.isBefore(YearMonth.now(clock))) {
			throw new MonthNotEndedException(month);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		checkNotRunning(month, now);

		JobParameters parameters = new JobParametersBuilder()
				.addString(InterestSettlementJobConfig.MONTH, month.toString())
				.addLocalDate(InterestSettlementJobConfig.UP_TO, month.atEndOfMonth())
				.addLocalDateTime(InterestSettlementJobConfig.REQUESTED_AT, now)
				.toJobParameters();
		JobExecution execution;
		try {
			execution = jobOperator.start(interestSettlementJob, parameters);
		} catch (JobExecutionAlreadyRunningException e) {
			throw new SettlementAlreadyRunningException(month);
		} catch (Exception e) {
			// 잡이 시작조차 못함(파라미터 오류 등). 요청 시각이 실행마다 달라 "이미 끝난 실행" 은 나오지 않는다.
			throw new IllegalStateException("이자 결산 배치를 시작하지 못했습니다: " + month, e);
		}
		if (execution.getStatus() != BatchStatus.COMPLETED) {
			log.error("이자 결산 배치 실패: month={}, jobExecutionId={}, status={}, cause={}", month, execution.getId(),
					execution.getStatus(), execution.getAllFailureExceptions());
		}
		return result(month, execution);
	}

	/**
	 * 같은 달 결산이 실행 중이면 거부한다. 서버가 중간에 죽어 STARTED 로 남은 실행은 STALE_AFTER 가 지나면 FAILED 로
	 * 정리하고 넘어간다. 그러지 않으면 그 달은 영영 결산할 수 없다(일 마감의 failStale 과 같은 이유).
	 *
	 * <p>두 요청이 이 확인을 동시에 통과해 같은 달이 두 번 돌 수는 있다. 그래도 돈은 두 번 나가지 않는다.
	 * 계좌를 잠그고 지급하며, 늦은 쪽은 적수 행 수 검사(InterestPayer)에서 실패해 그 계좌를 건너뛴다.
	 */
	private void checkNotRunning(YearMonth month, LocalDateTime now) {
		for (JobExecution running : jobRepository.findRunningJobExecutions(InterestSettlementJobConfig.JOB_NAME)) {
			if (!month.toString().equals(running.getJobParameters().getString(InterestSettlementJobConfig.MONTH))) {
				continue;
			}
			LocalDateTime startedAt = running.getStartTime() != null ? running.getStartTime() : running.getCreateTime();
			if (startedAt != null && startedAt.isBefore(now.minus(STALE_AFTER))) {
				log.warn("오래된 이자 결산 실행을 FAILED 로 정리: jobExecutionId={}, startedAt={}", running.getId(), startedAt);
				running.setStatus(BatchStatus.FAILED);
				running.setExitStatus(ExitStatus.FAILED.addExitDescription("서버 중단으로 끝나지 않은 실행"));
				running.setEndTime(now);
				jobRepository.update(running);
				continue;
			}
			throw new SettlementAlreadyRunningException(month);
		}
	}

	/**
	 * 잡 실행 결과. 지급 건수와 금액은 이 실행이 남긴 지급 기록(job_execution_id)에서 센다.
	 * 건너뛴 수(해지)는 processor 가 걸러 낸 수(FILTER_COUNT), 실패 수는 skip 한 수다.
	 */
	private SettlementResult result(YearMonth month, JobExecution execution) {
		long filtered = 0;
		long skippedOnError = 0;
		for (StepExecution step : execution.getStepExecutions()) {
			filtered += step.getFilterCount();
			skippedOnError += step.getSkipCount();
		}
		InterestPaymentRepository.Totals totals = payments.totalsByJobExecution(execution.getId());
		return new SettlementResult(month, execution.getId(), execution.getStatus().name(),
				Math.toIntExact(totals.paid()), Math.toIntExact(filtered), Math.toIntExact(skippedOnError),
				totals.gross(), totals.tax(), totals.net());
	}

	/**
	 * 해지 직전에 어제까지의 이자를 지급한다. 잔액에 더해지므로 해지 지급액에 함께 나간다.
	 * 호출하는 쪽(AccountService.close)이 계좌를 잠근 트랜잭션 안에서 부른다.
	 * 오늘 하루치는 아직 적수가 없어 붙지 않는다(README 한계).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<InterestPayment> payOnClosure(Account account) {
		return payer.payUnpaid(account, LocalDate.now(clock).minusDays(1), InterestTrigger.CLOSURE);
	}

	/**
	 * 계좌 이자 현황: 아직 지급하지 않은 적수(어제까지)와 그걸로 계산한 예상 이자, 최근 지급 12건.
	 *
	 * @param unpaid 지급 전 적수. 없으면 null
	 */
	public record AccountInterest(Long accountId, InterestAccruals.Unpaid unpaid, InterestCalculator.Interest estimate,
			List<InterestPayment> payments) {
	}

	@Transactional(readOnly = true)
	public AccountInterest accountInterest(Long accountId) {
		if (!accountRepository.existsById(accountId)) {
			throw new AccountNotFoundException(accountId);
		}
		Optional<InterestAccruals.Unpaid> unpaid = accruals.unpaid(accountId, LocalDate.now(clock).minusDays(1));
		return new AccountInterest(accountId, unpaid.orElse(null),
				unpaid.map(u -> InterestCalculator.of(u.balanceRateSum())).orElse(null),
				payments.findTop12ByAccountIdOrderByIdDesc(accountId));
	}

	@Transactional(readOnly = true)
	public Page<InterestPayment> recentPayments(int page, int size) {
		return payments.findAllByOrderByIdDesc(PageRequest.of(page, size));
	}

}
