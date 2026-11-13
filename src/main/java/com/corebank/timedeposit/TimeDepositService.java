package com.corebank.timedeposit;

import com.corebank.account.Account;
import com.corebank.account.AccountClosedException;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountRepository;
import com.corebank.account.AccountService;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.idempotency.IdempotentOperation;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.interest.InterestPayment;
import com.corebank.interest.InterestPaymentRepository;
import com.corebank.ledger.LedgerService;
import com.corebank.product.ProductService;
import com.corebank.product.TimeDepositProduct;
import com.corebank.transfer.Transfer;
import com.corebank.transfer.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 정기예금: 가입(open), 중도해지(terminate), 만기 처리(matureDue).
 *
 * <p>돈의 흐름
 * <pre>
 * 가입      연결계좌 ──원금──▶ 정기예금 계좌                (이체 기록, TRANSFER_OUT/IN)
 * 해지      정기예금 계좌 ◀──세후 이자── 은행 이자비용       (INTEREST, 이자 지급 기록)
 *           정기예금 계좌 ──원금+세후 이자──▶ 연결계좌      (이체 기록), 정기예금 계좌는 CLOSED
 * </pre>
 * 고객이 직접 하는 입출금·이체는 정기예금 계좌에서 막는다(Account.checkDemandTransactionsAllowed).
 *
 * <p>날짜(가입일·해지일·만기 판단)는 업무 날짜라 앱 시계(Clock)를 쓴다. 테스트가 시계를 옮겨 만기를 만든다.
 */
@Service
public class TimeDepositService {

	private static final Logger log = LoggerFactory.getLogger(TimeDepositService.class);

	private final TimeDepositRepository repository;
	private final AccountRepository accountRepository;
	private final AccountService accountService;
	private final ProductService productService;
	private final TransferService transferService;
	private final LedgerService ledgerService;
	private final InterestPaymentRepository interestPayments;
	private final IdempotencyService idempotencyService;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public TimeDepositService(TimeDepositRepository repository, AccountRepository accountRepository,
			AccountService accountService, ProductService productService, TransferService transferService,
			LedgerService ledgerService, InterestPaymentRepository interestPayments,
			IdempotencyService idempotencyService, PlatformTransactionManager transactionManager, Clock clock) {
		this.repository = repository;
		this.accountRepository = accountRepository;
		this.accountService = accountService;
		this.productService = productService;
		this.transferService = transferService;
		this.ledgerService = ledgerService;
		this.interestPayments = interestPayments;
		this.idempotencyService = idempotencyService;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * 해지 결과.
	 *
	 * @param payment 이자 지급 기록. 0일(가입 당일 해지)이면 null
	 * @param payout  연결계좌로 보낸 금액(원금 + 세후 이자)
	 */
	public record Settled(TimeDeposit deposit, InterestPayment payment, long payout) {
	}

	/**
	 * @param matured 이번에 만기 해지한 건수
	 * @param failed  오류로 처리하지 못한 건수. 다른 건은 계속 처리하고, 다음 실행에서 다시 시도한다
	 */
	public record MaturityResult(LocalDate businessDate, int matured, int failed) {
	}

	/**
	 * 가입. 연결계좌에서 원금을 꺼내 새 정기예금 계좌에 넣고, 약정(이율·만기일)을 기록한다. 한 트랜잭션이라 하나라도 실패하면
	 * 연결계좌 잔액도 되돌아간다.
	 *
	 * <p>연결계좌만 잠근다. 새 계좌는 아직 아무도 모르는 계좌라 잠글 필요가 없다. 같은 키로 다시 오면 잠금에서 기다렸다가
	 * 처음 만든 정기예금을 돌려준다.
	 *
	 * @throws com.corebank.product.ProductTypeMismatchException  입출금 상품
	 * @throws com.corebank.product.ProductNotAvailableException  판매 중지된 상품
	 * @throws com.corebank.account.TimeDepositRestrictedException 연결계좌가 정기예금
	 * @throws com.corebank.account.InsufficientBalanceException   연결계좌 잔액 부족
	 */
	@Transactional
	public IdempotentResult<TimeDeposit> open(Long productId, long principal, Long payoutAccountId, IdempotencyKey key) {
		TimeDepositProduct product = productService.forNewTimeDeposit(productId);
		Account payout = lock(payoutAccountId);
		String fingerprint = "TIME_DEPOSIT_OPEN|product=" + productId + "|principal=" + principal
				+ "|payout=" + payoutAccountId;
		Optional<Long> completed = idempotencyService.findCompleted(key, fingerprint);
		if (completed.isPresent()) {
			return IdempotentResult.replayed(get(completed.get()));
		}

		payout.checkDemandTransactionsAllowed();
		payout.withdraw(principal);
		Account account = accountService.openTimeDepositAccount(payout, product);
		account.deposit(principal);
		transferService.recordInternal(payout, account, principal);
		TimeDeposit deposit = repository.save(
				TimeDeposit.open(account, product, principal, payoutAccountId, LocalDate.now(clock)));
		idempotencyService.record(key, IdempotentOperation.TIME_DEPOSIT_OPEN, fingerprint, account.getId());
		return IdempotentResult.created(deposit);
	}

	/**
	 * 중도해지. 오늘이 만기일 이후면(만기 처리가 아직 돌지 않음) 만기 해지로 처리한다. 고객이 손해 볼 이유가 없다.
	 * 같은 키로 다시 오면 처음 결과를 돌려준다(이미 해지돼도 409 가 아니다). 다른 키로 해지된 예금을 해지하면 409 ACCOUNT_CLOSED.
	 */
	@Transactional
	public IdempotentResult<Settled> terminate(Long accountId, IdempotencyKey key) {
		Locked locked = lockBoth(accountId);
		String fingerprint = "TIME_DEPOSIT_CLOSE|account=" + accountId;
		Optional<Long> completed = idempotencyService.findCompleted(key, fingerprint);
		if (completed.isPresent()) {
			return IdempotentResult.replayed(settledView(locked.deposit()));
		}
		Settled settled = settle(locked, LocalDate.now(clock));
		idempotencyService.record(key, IdempotentOperation.TIME_DEPOSIT_CLOSE, fingerprint, accountId);
		return IdempotentResult.created(settled);
	}

	/**
	 * 오늘까지 만기가 된 정기예금을 모두 만기 해지한다. 한 건씩 따로 트랜잭션을 연다. 한 건이 실패해도(연결계좌 이상 등)
	 * 다른 고객의 만기 지급은 계속되고, 실패한 건은 ACTIVE 로 남아 다음 실행에서 다시 시도한다.
	 *
	 * <p>여러 번 실행해도 된다. 이미 해지한 건은 대상 목록에 없고, 목록을 읽은 뒤 고객이 먼저 해지했으면 잠금 뒤 상태 확인에서 건너뛴다.
	 * 하루 만기 건수는 많지 않아 배치(Spring Batch) 대신 반복문으로 처리한다. 수만 건이면 이자 결산처럼 청크 배치로 옮긴다.
	 */
	public MaturityResult matureDue() {
		LocalDate today = LocalDate.now(clock);
		int matured = 0;
		int failed = 0;
		for (Long id : repository.findDueIds(today)) {
			try {
				Boolean done = transactionTemplate.execute(status -> {
					Locked locked = lockBoth(id);
					if (!locked.deposit().isDue(today)) {
						return false;
					}
					settle(locked, today);
					return true;
				});
				if (Boolean.TRUE.equals(done)) {
					matured++;
				}
			} catch (RuntimeException e) {
				failed++;
				log.error("정기예금 만기 처리 실패: accountId={}", id, e);
			}
		}
		return new MaturityResult(today, matured, failed);
	}

	/**
	 * 해지 한 건: 이자 지급 → 정기예금 계좌 해지 → 원리금을 연결계좌로 이체 → 계약에 해지 기록. 두 계좌는 잠겨 있다.
	 *
	 * <p>이자 지급 기록은 saveAndFlush 로 바로 INSERT 한다. 계약의 interest_payment_id 외래키가 가리킬 행이 먼저 있어야 한다
	 * (JPA 가 INSERT 순서를 엔티티 저장 순서대로 보내긴 하지만, 바로 아래 UPDATE 와 섞일 때 순서를 기대지 않는다).
	 */
	private Settled settle(Locked locked, LocalDate today) {
		TimeDeposit deposit = locked.deposit();
		Account account = locked.account();
		Account payoutAccount = locked.payoutAccount();
		if (!deposit.isActive()) {
			throw new AccountClosedException(account.getId());
		}
		TimeDepositInterest.Settlement settlement = deposit.quote(today);

		InterestPayment payment = null;
		if (settlement.period() != null) {
			Long ledgerTransactionId = null;
			if (settlement.interest().gross() > 0) {
				account.deposit(settlement.interest().net());
				ledgerTransactionId = ledgerService.recordInterest(account.getId(), settlement.interest().gross(),
						settlement.interest().tax(), account.getBalance()).getId();
			}
			payment = interestPayments.saveAndFlush(InterestPayment.timeDeposit(account.getId(), settlement.trigger(),
					settlement.period(), settlement.interest(), ledgerTransactionId));
		}

		long payout = account.close();
		payoutAccount.deposit(payout);
		Transfer transfer = transferService.recordInternal(account, payoutAccount, payout);
		deposit.settle(settlement, payment == null ? null : payment.getId(), transfer.getId());
		return new Settled(deposit, payment, payout);
	}

	/** 처음 해지 결과를 다시 만든다(멱등성 재전송). 연결계좌로 보낸 금액은 원금 + 세후 이자다 */
	private Settled settledView(TimeDeposit deposit) {
		InterestPayment payment = deposit.getInterestPaymentId() == null
				? null
				: interestPayments.findById(deposit.getInterestPaymentId()).orElseThrow();
		long net = payment == null ? 0 : payment.getNetAmount();
		return new Settled(deposit, payment, deposit.getPrincipal() + net);
	}

	/** 정기예금 조회. 계약과 계좌를 함께 본다 */
	@Transactional(readOnly = true)
	public TimeDeposit get(Long accountId) {
		return repository.findById(accountId).orElseThrow(() -> new TimeDepositNotFoundException(accountId));
	}

	/**
	 * 정기예금 상세: 계약, 만기 예상 이자, 오늘 해지하면 받을 금액(가입 중일 때), 해지 결과(해지했을 때).
	 *
	 * @param quote   오늘 해지 예상. 해지한 예금이면 null
	 * @param settled 해지 결과. 가입 중이면 null
	 */
	public record Detail(TimeDeposit deposit, Account account, String productName, TimeDepositInterest.Settlement atMaturity,
			TimeDepositInterest.Settlement quote, Settled settled) {
	}

	@Transactional(readOnly = true)
	public Detail detail(Long accountId) {
		TimeDeposit deposit = get(accountId);
		// 지연 로딩 프록시(account, product)를 트랜잭션 안에서 읽어 둔다. 트랜잭션이 끝난 뒤 읽으면 LazyInitializationException 이다.
		Account account = deposit.getAccount();
		account.getBalance();
		String productName = deposit.getProduct().getName();
		return new Detail(deposit, account, productName, deposit.atMaturity(),
				deposit.isActive() ? deposit.quote(LocalDate.now(clock)) : null,
				deposit.isActive() ? null : settledView(deposit));
	}

	@Transactional(readOnly = true)
	public Page<TimeDeposit> search(TimeDepositStatus status, int page, int size) {
		return repository.search(status, PageRequest.of(page, size));
	}

	private record Locked(TimeDeposit deposit, Account account, Account payoutAccount) {
	}

	/**
	 * 정기예금 계좌와 연결계좌를 잠근다. 이체와 같이 id 가 작은 계좌부터 잠가 데드락을 피한다(연결계좌에서 다른 이체가 동시에 올 수 있다).
	 *
	 * <p>계약 엔티티는 <b>잠금 뒤에 처음</b> 읽는다. 잠금 전에 findById 로 읽어 두면 그 객체가 영속성 컨텍스트(1차 캐시)에 남고,
	 * 잠금 뒤의 findById 는 DB 를 다시 보지 않고 캐시의 옛 객체를 돌려준다. 그 사이 다른 요청이 해지했어도 ACTIVE 로 보여
	 * 두 번 지급할 수 있다. 그래서 잠금 전에는 연결계좌 번호만 값으로 읽는다(값 조회는 캐시에 엔티티를 올리지 않는다).
	 */
	private Locked lockBoth(Long accountId) {
		// 연결계좌 번호는 바뀌지 않으므로(updatable = false) 잠금 전에 읽어도 된다.
		Long payoutAccountId = repository.findPayoutAccountId(accountId)
				.orElseThrow(() -> new TimeDepositNotFoundException(accountId));
		Account first = lock(Math.min(accountId, payoutAccountId));
		Account second = lock(Math.max(accountId, payoutAccountId));
		Account account = first.getId().equals(accountId) ? first : second;
		Account payoutAccount = account == first ? second : first;
		TimeDeposit deposit = repository.findById(accountId).orElseThrow(() -> new TimeDepositNotFoundException(accountId));
		return new Locked(deposit, account, payoutAccount);
	}

	private Account lock(Long id) {
		return accountRepository.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
	}

}
