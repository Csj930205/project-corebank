package com.corebank.interest;

import com.corebank.account.Account;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountRepository;
import com.corebank.account.AccountStatus;
import com.corebank.closing.BusinessDateNotEndedException;
import com.corebank.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 이자: 매일 적수를 쌓고(accrue), 매달 지급한다(settle). 해지할 때는 그때까지의 이자를 함께 준다(payOnClosure).
 */
@Service
public class InterestService {

	private static final Logger log = LoggerFactory.getLogger(InterestService.class);

	private final InterestAccruals accruals;
	private final InterestPaymentRepository payments;
	private final AccountRepository accountRepository;
	private final LedgerService ledgerService;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public InterestService(InterestAccruals accruals, InterestPaymentRepository payments,
			AccountRepository accountRepository, LedgerService ledgerService, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.accruals = accruals;
		this.payments = payments;
		this.accountRepository = accountRepository;
		this.ledgerService = ledgerService;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** @param accrued 새로 쌓은 계좌 수. 이미 쌓인 날을 다시 실행하면 0 */
	public record AccrualResult(LocalDate businessDate, int accrued) {
	}

	/**
	 * @param paid    이자를 지급한 계좌 수(0원 포함)
	 * @param skipped 해지돼서 건너뛴 계좌 수
	 * @param failed  오류로 지급하지 못한 계좌 수. 다른 계좌는 계속 처리하고, 같은 달을 다시 실행하면 이 계좌만 다시 시도한다
	 */
	public record SettlementResult(YearMonth month, int paid, int skipped, int failed, long totalGross, long totalTax,
			long totalNet) {
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
	 *
	 * <p>트랜잭션을 계좌마다 따로 연다(TransactionTemplate). 하나로 묶으면 계좌 한 곳의 오류로 수십만 계좌의 이자가 모두
	 * 취소되고, 그동안 모든 계좌를 잠근 채로 있게 된다. 이미 지급한 계좌는 적수에 지급 번호가 남아 다시 실행해도 건너뛴다.
	 *
	 * @throws MonthNotEndedException 이번 달이나 미래 달
	 */
	public SettlementResult settle(YearMonth month) {
		if (!month.isBefore(YearMonth.now(clock))) {
			throw new MonthNotEndedException(month);
		}
		LocalDate upTo = month.atEndOfMonth();
		int paid = 0;
		int skipped = 0;
		int failed = 0;
		long gross = 0;
		long tax = 0;
		long net = 0;
		for (Long accountId : accruals.accountsWithUnpaid(upTo)) {
			try {
				Optional<InterestPayment> payment = transaction.execute(status -> settleAccount(accountId, upTo));
				if (payment == null || payment.isEmpty()) {
					skipped++;
					continue;
				}
				paid++;
				gross += payment.get().getGrossAmount();
				tax += payment.get().getIncomeTax() + payment.get().getLocalTax();
				net += payment.get().getNetAmount();
			} catch (RuntimeException e) {
				failed++;
				log.error("이자 지급 실패: accountId={}, month={}", accountId, month, e);
			}
		}
		return new SettlementResult(month, paid, skipped, failed, gross, tax, net);
	}

	/** 한 계좌의 결산. 해지된 계좌는 건너뛴다(해지할 때 이자를 이미 받았다). */
	private Optional<InterestPayment> settleAccount(Long accountId, LocalDate upTo) {
		Account account = accountRepository.findByIdForUpdate(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		if (account.getStatus() == AccountStatus.CLOSED) {
			return Optional.empty();
		}
		return pay(account, upTo, InterestTrigger.SETTLEMENT);
	}

	/**
	 * 해지 직전에 어제까지의 이자를 지급한다. 잔액에 더해지므로 해지 지급액에 함께 나간다.
	 * 호출하는 쪽(AccountService.close)이 계좌를 잠근 트랜잭션 안에서 부른다.
	 * 오늘 하루치는 아직 적수가 없어 붙지 않는다(README 한계).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<InterestPayment> payOnClosure(Account account) {
		return pay(account, LocalDate.now(clock).minusDays(1), InterestTrigger.CLOSURE);
	}

	/**
	 * 지급 한 건. 계좌는 잠겨 있어야 한다.
	 *
	 * <p>순서가 중요하다.
	 * <ol>
	 *   <li>적수 합계로 이자와 세금을 계산하고 잔액을 늘린 뒤 원장에 기록한다.</li>
	 *   <li>지급 기록을 saveAndFlush 로 <b>바로 INSERT</b> 한다. 다음 단계의 UPDATE 는 JdbcTemplate 로 나가는데,
	 *       JdbcTemplate 은 JPA 가 아직 DB 에 보내지 않은(영속성 컨텍스트에만 있는) 행을 모른다.
	 *       flush 하지 않으면 payment_id 외래키가 가리킬 행이 DB 에 없어 실패한다.</li>
	 *   <li>적수에 지급 번호를 채운다. 바뀐 행 수가 합계를 낸 행 수와 다르면(그 사이 적수가 더 쌓임) 통째로 되돌린다.</li>
	 * </ol>
	 */
	private Optional<InterestPayment> pay(Account account, LocalDate upTo, InterestTrigger trigger) {
		Optional<InterestAccruals.Unpaid> unpaid = accruals.unpaid(account.getId(), upTo);
		if (unpaid.isEmpty()) {
			return Optional.empty();
		}
		InterestCalculator.Interest interest = InterestCalculator.of(unpaid.get().balanceRateSum());
		Long ledgerTransactionId = null;
		if (interest.gross() > 0) {
			account.deposit(interest.net());
			ledgerTransactionId = ledgerService.recordInterest(account.getId(), interest.gross(), interest.tax(),
					account.getBalance()).getId();
		}
		InterestPayment payment = payments.saveAndFlush(
				InterestPayment.of(account.getId(), trigger, unpaid.get(), interest, ledgerTransactionId));
		int marked = accruals.markPaid(account.getId(), upTo, payment.getId());
		if (marked != unpaid.get().days()) {
			throw new IllegalStateException("적수가 지급 중에 바뀌었습니다. accountId=" + account.getId());
		}
		return Optional.of(payment);
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
