package com.corebank.closing;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 일 마감 대사 배치(dailyClosingJob). 단계는 네 개이고 순서대로 실행된다.
 * <pre>
 * 1. dailyTotalsStep       (tasklet) 일계표: 영업일 거래를 종류별로 집계
 * 2. accountReconcileStep  (chunk)   계좌별 대사: 계좌를 100개씩 읽어 잔액과 원장을 비교
 * 3. ledgerCheckStep       (tasklet) 원장 전체 점검: 거래별 차대 일치, 반쪽 이체, 이체 가계정
 * 4. finishStep            (tasklet) 집계 값과 결과 상태(BALANCED / UNBALANCED) 기록
 * </pre>
 *
 * <p>잡 파라미터는 closingId(daily_closing.id)와 businessDate 두 개다. closingId 가 실행마다 다르므로
 * 같은 영업일을 다시 마감하면 새 JobInstance 가 된다. 대사는 읽고 결과만 새로 쓰는 일이라, 실패한 실행을 이어서
 * 재시작(restart)하지 않고 처음부터 다시 실행한다.
 *
 * <p>계좌 대사만 청크 방식인 이유: 계좌 수만큼 늘어나는 유일한 단계라, 한 번에 다 읽지 않고 100개씩 읽고 쓰고
 * 커밋한다. 나머지는 SQL 한 문장으로 DB 안에서 끝나므로 tasklet 으로 충분하다.
 */
@Configuration
public class DailyClosingJobConfig {

	public static final String JOB_NAME = "dailyClosingJob";
	public static final String CLOSING_ID = "closingId";
	public static final String BUSINESS_DATE = "businessDate";

	static final int CHUNK_SIZE = 100;

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final ClosingRepository closingRepository;
	private final Clock clock;

	public DailyClosingJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			ClosingRepository closingRepository, Clock clock) {
		this.jobRepository = jobRepository;
		this.transactionManager = transactionManager;
		this.closingRepository = closingRepository;
		this.clock = clock;
	}

	@Bean
	public Job dailyClosingJob(Step dailyTotalsStep, Step accountReconcileStep, Step ledgerCheckStep, Step finishStep) {
		return new JobBuilder(JOB_NAME, jobRepository)
				.listener(closingStatusListener())
				.start(dailyTotalsStep)
				.next(accountReconcileStep)
				.next(ledgerCheckStep)
				.next(finishStep)
				.build();
	}

	@Bean
	public Step dailyTotalsStep() {
		return new StepBuilder("dailyTotalsStep", jobRepository)
				.tasklet((contribution, chunkContext) -> {
					ClosingWindow window = window(contribution);
					closingRepository.insertDailyTotals(closingId(contribution), window.from(), window.cutoff());
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.build();
	}

	@Bean
	public Step accountReconcileStep(JdbcPagingItemReader<AccountSnapshot> accountSnapshotReader,
			ItemWriter<AccountReconciliation> mismatchWriter) {
		return new StepBuilder("accountReconcileStep", jobRepository)
				.<AccountSnapshot, AccountReconciliation>chunk(CHUNK_SIZE)
				.transactionManager(transactionManager)
				.reader(accountSnapshotReader)
				.processor(new AccountReconcileProcessor())
				.writer(mismatchWriter)
				.build();
	}

	/**
	 * 마감 시점까지 개설된 계좌를 id 순으로 100개씩 읽는다. 계좌마다 원장 합계 두 개와 마감 시점 거래 후 잔액을
	 * 상관 서브쿼리로 함께 계산한다. ledger_entry(account_id), ledger_transaction(account_id, id) 인덱스를 탄다.
	 *
	 * <p>페이징은 OFFSET 이 아니라 "마지막으로 읽은 id 다음부터"(sortKey) 방식이라 뒤쪽 페이지도 느려지지 않는다.
	 * @StepScope: 실행마다 잡 파라미터(businessDate)로 새로 만든다.
	 */
	@Bean
	@StepScope
	public JdbcPagingItemReader<AccountSnapshot> accountSnapshotReader(DataSource dataSource,
			@Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate) throws Exception {
		String signed = ClosingRepository.SIGNED_AMOUNT;
		return new JdbcPagingItemReaderBuilder<AccountSnapshot>()
				.name("accountSnapshotReader")
				.dataSource(dataSource)
				.pageSize(CHUNK_SIZE)
				.selectClause("""
						SELECT a.id, a.account_number, a.balance,
						       (SELECT COALESCE(SUM(%1$s), 0) FROM ledger_entry e
						        WHERE e.account_id = a.id AND e.ledger_account = 'CUSTOMER_DEPOSIT') AS ledger_total,
						       (SELECT COALESCE(SUM(%1$s), 0)
						        FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
						        WHERE e.account_id = a.id AND e.ledger_account = 'CUSTOMER_DEPOSIT'
						          AND t.created_at < :cutoff) AS ledger_at_cutoff,
						       (SELECT MAX(t.balance_after) KEEP (DENSE_RANK LAST ORDER BY t.id)
						        FROM ledger_transaction t
						        WHERE t.account_id = a.id AND t.created_at < :cutoff) AS balance_after_at_cutoff
						""".formatted(signed))
				.fromClause("FROM account a")
				.whereClause("WHERE a.created_at < :cutoff")
				.sortKeys(Map.of("id", Order.ASCENDING))
				.parameterValues(Map.of("cutoff", Timestamp.valueOf(new ClosingWindow(businessDate).cutoff())))
				.rowMapper((rs, row) -> new AccountSnapshot(
						rs.getLong("id"),
						rs.getString("account_number"),
						rs.getBigDecimal("balance"),
						rs.getBigDecimal("ledger_total"),
						rs.getBigDecimal("ledger_at_cutoff"),
						rs.getBigDecimal("balance_after_at_cutoff")))
				.build();
	}

	/** 청크(최대 100개 계좌)에서 나온 불일치를 모아 한 번의 배치 INSERT 로 쓴다. */
	@Bean
	@StepScope
	public ItemWriter<AccountReconciliation> mismatchWriter(
			@Value("#{jobParameters['" + CLOSING_ID + "']}") Long closingId) {
		return chunk -> {
			List<ClosingMismatch> mismatches = chunk.getItems().stream()
					.flatMap(item -> item.mismatches().stream())
					.toList();
			if (!mismatches.isEmpty()) {
				closingRepository.insertMismatches(closingId, mismatches);
			}
		};
	}

	@Bean
	public Step ledgerCheckStep() {
		return new StepBuilder("ledgerCheckStep", jobRepository)
				.tasklet((contribution, chunkContext) -> {
					long closingId = closingId(contribution);
					ClosingWindow window = window(contribution);
					closingRepository.insertUnbalancedTransactions(closingId, window.from(), window.cutoff());
					closingRepository.insertIncompleteTransfers(closingId, window.from(), window.cutoff());
					closingRepository.insertClearingNotZero(closingId, window.cutoff());
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.build();
	}

	@Bean
	public Step finishStep() {
		return new StepBuilder("finishStep", jobRepository)
				.tasklet((contribution, chunkContext) -> {
					closingRepository.finish(closingId(contribution), window(contribution).cutoff(), LocalDateTime.now(clock));
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.build();
	}

	/**
	 * 잡 시작 때 daily_closing 에 Spring Batch 실행 번호를 연결하고, 잡이 실패하면 FAILED 와 원인을 남긴다.
	 * 실패하면 finishStep 까지 가지 못해 RUNNING 으로 남기 때문에, 여기서 정리하지 않으면 그 영업일을 다시 마감할 수 없다.
	 */
	private JobExecutionListener closingStatusListener() {
		return new JobExecutionListener() {
			@Override
			public void beforeJob(JobExecution jobExecution) {
				closingRepository.attachJobExecution(closingId(jobExecution.getJobParameters()), jobExecution.getId());
			}

			@Override
			public void afterJob(JobExecution jobExecution) {
				if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
					String cause = jobExecution.getAllFailureExceptions().stream()
							.findFirst()
							.map(e -> e.getClass().getSimpleName() + ": " + e.getMessage())
							.orElse(jobExecution.getExitStatus().getExitDescription());
					closingRepository.markFailed(closingId(jobExecution.getJobParameters()),
							"배치 실패(" + jobExecution.getStatus() + "): " + cause, LocalDateTime.now(clock));
				}
			}
		};
	}

	private static long closingId(StepContribution contribution) {
		return closingId(contribution.getStepExecution().getJobParameters());
	}

	private static long closingId(JobParameters parameters) {
		return parameters.getLong(CLOSING_ID);
	}

	private static ClosingWindow window(StepContribution contribution) {
		return new ClosingWindow(contribution.getStepExecution().getJobParameters().getLocalDate(BUSINESS_DATE));
	}

}
