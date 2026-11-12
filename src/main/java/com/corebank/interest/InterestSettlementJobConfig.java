package com.corebank.interest;

import com.corebank.account.Account;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountRepository;
import com.corebank.account.AccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.LimitCheckingExceptionHierarchySkipPolicy;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * 월 이자 결산 배치(interestSettlementJob). 단계는 하나다.
 * <pre>
 * interestSettlementStep (chunk 100)
 *   reader    적수가 남은 계좌를 id 순으로 100개씩 읽는다. 계좌마다 적수 요약(기간·일수·Σ잔액×이율)을 함께 읽는다.
 *   processor 이자와 세금을 계산한다. 해지된 계좌는 걸러 낸다(DB 를 보지 않는 순수 계산).
 *   writer    계좌를 잠그고 잔액·원장·지급 기록·적수 표시를 한 트랜잭션으로 쓴다.
 * </pre>
 *
 * <p><b>chunk 와 skip.</b> 100개 계좌를 한 트랜잭션으로 커밋한다. 계좌 한 곳이 실패하면(예: 잔액 한도 초과) 그 chunk 는
 * 통째로 롤백되고, Spring Batch 가 같은 100개를 한 건씩 따로 트랜잭션을 열어 다시 쓴다(chunk scan). 실패한 계좌만
 * 건너뛰고(skip) 나머지는 지급된다. 건너뛴 계좌의 적수는 지급되지 않은 채 남아, 같은 달을 다시 결산하면 그 계좌만 다시
 * 시도한다. 전에는 계좌마다 트랜잭션을 열었는데(커밋 N번), 이제 정상일 때는 커밋이 N/100 번이다.
 *
 * <p>건너뛰기는 {@code corebank.interest.settlement.skip-limit} 건까지다. 그보다 많이 실패하면 DB 장애처럼 계좌 문제가
 * 아닌 원인일 가능성이 커서, 계속 건너뛰지 않고 잡을 실패시킨다.
 *
 * <p><b>재실행.</b> 잡 파라미터에 요청 시각(requestedAt)이 있어 실행할 때마다 새 JobInstance 다. 실패한 실행을 이어서
 * 재시작(restart)하지 않는다. 이미 지급한 계좌는 적수에 지급 번호가 있어 reader 가 다시 읽지 않으므로, 처음부터 다시
 * 실행해도 남은 계좌만 지급한다.
 */
@Configuration
public class InterestSettlementJobConfig {

	private static final Logger log = LoggerFactory.getLogger(InterestSettlementJobConfig.class);

	public static final String JOB_NAME = "interestSettlementJob";
	/** 결산 달. "2026-11". 같은 달 실행이 겹치는지 볼 때 쓴다 */
	public static final String MONTH = "month";
	/** 결산 기준일(그 달 말일). 이날까지의 적수를 지급한다 */
	public static final String UP_TO = "upTo";
	public static final String REQUESTED_AT = "requestedAt";

	static final int CHUNK_SIZE = 100;

	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;

	public InterestSettlementJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		this.jobRepository = jobRepository;
		this.transactionManager = transactionManager;
	}

	@Bean
	public Job interestSettlementJob(Step interestSettlementStep) {
		return new JobBuilder(JOB_NAME, jobRepository)
				.start(interestSettlementStep)
				.build();
	}

	@Bean
	public Step interestSettlementStep(JdbcPagingItemReader<SettlementTarget> settlementTargetReader,
			ItemWriter<InterestSettlement> interestPaymentWriter,
			@Value("${corebank.interest.settlement.skip-limit:1000}") long skipLimit) {
		return new StepBuilder("interestSettlementStep", jobRepository)
				.<SettlementTarget, InterestSettlement>chunk(CHUNK_SIZE)
				.transactionManager(transactionManager)
				.reader(settlementTargetReader)
				.processor(new InterestSettlementProcessor())
				.writer(interestPaymentWriter)
				.faultTolerant()
				.skipPolicy(new LimitCheckingExceptionHierarchySkipPolicy(Set.of(Exception.class), skipLimit))
				.skipListener(skipLogger())
				.build();
	}

	/**
	 * upTo 이하에 지급하지 않은 적수가 있는 계좌를 id 순으로 100개씩 읽는다.
	 *
	 * <p>CROSS APPLY: 계좌 한 줄마다 오른쪽 서브쿼리를 그 계좌로 한 번 실행한다(LATERAL 조인). 집계 함수만 있고
	 * GROUP BY 가 없으면 행이 없어도 한 줄(days = 0)이 나오므로, days > 0 으로 적수가 남은 계좌만 남긴다.
	 * 적수 테이블 전체를 GROUP BY 해 놓고 조인하면 페이지(100개)마다 전체 적수를 다시 집계한다.
	 * 여기서는 계좌마다 PK(account_id, accrual_date) 인덱스로 그 계좌 적수만 본다.
	 *
	 * <p>페이징이 OFFSET 이 아니라 "마지막으로 읽은 id 다음부터"(sortKey) 인 것이 이 잡에서는 정확성 문제다. 지급한 계좌는
	 * 적수에 지급 번호가 채워져 조건에서 빠진다. OFFSET 이면 결과가 앞에서부터 줄어드는 바람에 다음 페이지가 밀려
	 * 계좌를 건너뛴다. id 기준이면 이미 지나간 계좌가 빠져도 다음 페이지 위치가 변하지 않는다.
	 */
	@Bean
	@StepScope
	public JdbcPagingItemReader<SettlementTarget> settlementTargetReader(DataSource dataSource,
			@Value("#{jobParameters['" + UP_TO + "']}") LocalDate upTo) throws Exception {
		return new JdbcPagingItemReaderBuilder<SettlementTarget>()
				.name("settlementTargetReader")
				.dataSource(dataSource)
				.pageSize(CHUNK_SIZE)
				.selectClause("SELECT a.id, a.status, u.from_date, u.to_date, u.days, u.balance_rate_sum")
				.fromClause("""
						FROM account a CROSS APPLY (
						    SELECT MIN(i.accrual_date) AS from_date, MAX(i.accrual_date) AS to_date,
						           COUNT(*) AS days, SUM(i.balance * i.annual_rate) AS balance_rate_sum
						    FROM interest_accrual i
						    WHERE i.account_id = a.id AND i.payment_id IS NULL AND i.accrual_date <= :upTo
						) u
						""")
				.whereClause("WHERE u.days > 0")
				.sortKeys(Map.of("id", Order.ASCENDING))
				.parameterValues(Map.of("upTo", Date.valueOf(upTo)))
				.rowMapper((rs, row) -> new SettlementTarget(
						rs.getLong("id"),
						AccountStatus.CLOSED.name().equals(rs.getString("status")),
						new InterestAccruals.Unpaid(rs.getDate("from_date").toLocalDate(),
								rs.getDate("to_date").toLocalDate(), rs.getInt("days"),
								rs.getBigDecimal("balance_rate_sum"))))
				.build();
	}

	/**
	 * chunk 의 계좌를 하나씩 잠그고 지급한다. 모두 chunk 트랜잭션 하나 안에서 일어나므로, 중간에 한 건이 실패하면
	 * 앞서 지급한 계좌까지 롤백된 뒤 한 건씩 다시 쓴다(위 클래스 주석).
	 *
	 * <p>읽은 뒤 잠그기 전에 계좌가 해지됐으면 건너뛴다. 해지할 때 이자를 이미 받았다(적수에 지급 번호가 채워졌다).
	 * 그 사이 다른 결산이 먼저 지급했으면 InterestPayer 의 행 수 검사가 실패해 이 계좌만 건너뛴다. 두 번 지급하지 않는다.
	 */
	@Bean
	@StepScope
	public ItemWriter<InterestSettlement> interestPaymentWriter(AccountRepository accountRepository, InterestPayer payer,
			@Value("#{jobParameters['" + UP_TO + "']}") LocalDate upTo,
			@Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
		return chunk -> {
			for (InterestSettlement item : chunk) {
				Account account = accountRepository.findByIdForUpdate(item.accountId())
						.orElseThrow(() -> new AccountNotFoundException(item.accountId()));
				if (account.getStatus() == AccountStatus.CLOSED) {
					continue;
				}
				payer.pay(account, upTo, item.unpaid(), item.interest(), InterestTrigger.SETTLEMENT, jobExecutionId);
			}
		};
	}

	/** 건너뛴 계좌를 남긴다. 실행 이력에는 건수만 남아, 어느 계좌가 왜 실패했는지는 로그로 본다. */
	private static SkipListener<SettlementTarget, InterestSettlement> skipLogger() {
		return new SkipListener<>() {
			@Override
			public void onSkipInRead(Throwable t) {
				log.error("이자 결산 읽기 실패(건너뜀)", t);
			}

			@Override
			public void onSkipInProcess(SettlementTarget item, Throwable t) {
				log.error("이자 계산 실패(건너뜀): accountId={}", item.accountId(), t);
			}

			@Override
			public void onSkipInWrite(InterestSettlement item, Throwable t) {
				log.error("이자 지급 실패(건너뜀): accountId={}", item.accountId(), t);
			}
		};
	}

}
