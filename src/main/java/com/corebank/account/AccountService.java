package com.corebank.account;

import com.corebank.customer.Customer;
import com.corebank.customer.CustomerService;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.idempotency.IdempotentOperation;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.ledger.LedgerHistoryEntry;
import com.corebank.ledger.LedgerService;
import com.corebank.ledger.LedgerTransaction;
import com.corebank.product.DepositProduct;
import com.corebank.product.ProductService;
import com.corebank.product.TimeDepositProduct;
import com.corebank.interest.InterestService;
import com.corebank.timedeposit.TimeDepositRepository;
import com.corebank.timedeposit.TimeDepositStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 계좌 업무 로직 계층. 컨트롤러는 HTTP 를, 이 클래스는 트랜잭션과 업무 규칙을 담당한다.
 */
@Service
public class AccountService {

	private static final String BANK_CODE = "110";

	private final AccountRepository accountRepository;
	private final LedgerService ledgerService;
	private final IdempotencyService idempotencyService;
	private final CustomerService customerService;
	private final ProductService productService;
	private final InterestService interestService;
	private final TimeDepositRepository timeDepositRepository;

	public AccountService(AccountRepository accountRepository, LedgerService ledgerService,
			IdempotencyService idempotencyService, CustomerService customerService, ProductService productService,
			InterestService interestService, TimeDepositRepository timeDepositRepository) {
		this.interestService = interestService;
		this.timeDepositRepository = timeDepositRepository;
		this.accountRepository = accountRepository;
		this.ledgerService = ledgerService;
		this.idempotencyService = idempotencyService;
		this.customerService = customerService;
		this.productService = productService;
	}

	/**
	 * 계좌를 개설한다. 계좌번호는 은행 코드와 10자리 일련번호를 붙여 만든다. (예: 1100000000001)
	 *
	 * <p>트랜잭션이 롤백되어도 시퀀스 값은 되돌아가지 않으므로 번호에 빈 값이 생길 수 있다. 중복만 없으면 문제없다.
	 */
	@Transactional
	public Account open(String ownerName) {
		return open(ownerName, null);
	}

	/**
	 * 상품을 골라 계좌를 연다. productId 가 null 이면 기본 상품이다.
	 *
	 * @throws com.corebank.product.ProductNotFoundException     없는 상품
	 * @throws com.corebank.product.ProductNotAvailableException 판매 중지된 상품
	 */
	@Transactional
	public Account open(String ownerName, Long productId) {
		DepositProduct product = productService.forNewAccount(productId);
		return accountRepository.save(Account.open(nextAccountNumber(), ownerName, product));
	}

	/** 고객 명의로 계좌를 연다. 예금주 이름은 가입할 때 적은 고객 이름이다. */
	@Transactional
	public Account openForCustomer(Long customerId) {
		return openForCustomer(customerId, null);
	}

	@Transactional
	public Account openForCustomer(Long customerId, Long productId) {
		Customer customer = customerService.get(customerId);
		DepositProduct product = productService.forNewAccount(productId);
		return accountRepository.save(Account.openFor(nextAccountNumber(), customer.getName(), customer.getId(), product));
	}

	/**
	 * 정기예금 계좌를 연다. 예금주와 고객은 연결계좌와 같다(관리자가 이름만으로 연 연결계좌면 정기예금도 주인 없는 계좌다).
	 * 원금 이동과 계약 기록은 TimeDepositService 가 같은 트랜잭션에서 한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Account openTimeDepositAccount(Account payoutAccount, TimeDepositProduct product) {
		return accountRepository.save(Account.openFor(nextAccountNumber(), payoutAccount.getOwnerName(),
				payoutAccount.getCustomerId(), product));
	}

	/** 고객의 계좌 목록(해지 계좌 포함, 최신 개설순). */
	@Transactional(readOnly = true)
	public List<Account> listByCustomer(Long customerId) {
		return accountRepository.findByCustomerIdOrderByIdDesc(customerId);
	}

	private String nextAccountNumber() {
		return BANK_CODE + "%010d".formatted(accountRepository.nextAccountNumberSequence());
	}

	/**
	 * 수취인 조회. 이체 받을 수 있는 계좌인지 확인하고 돌려준다. 누구 계좌든 조회되므로(받는 계좌는 보통 남의 계좌다)
	 * 호출하는 쪽은 가린 정보만 내보내야 한다(RecipientResponse).
	 *
	 * @throws AccountNotFoundException 없는 계좌번호
	 * @throws AccountClosedException   해지된 계좌. 받을 수 없으므로 이체 전에 알려 준다
	 */
	@Transactional(readOnly = true)
	public Account findRecipient(String accountNumber) {
		Account account = accountRepository.findByAccountNumber(accountNumber)
				.orElseThrow(() -> AccountNotFoundException.byNumber(accountNumber));
		if (account.getStatus() == AccountStatus.CLOSED) {
			throw AccountClosedException.byNumber(accountNumber);
		}
		return account;
	}

	/**
	 * 계좌번호를 id 로 바꾼다(이체의 받는 계좌). 해지 여부는 보지 않는다. 이체가 잠금을 잡은 뒤 확인해야 정확하기 때문이다.
	 *
	 * @throws AccountNotFoundException 없는 계좌번호
	 */
	@Transactional(readOnly = true)
	public Long idOf(String accountNumber) {
		return accountRepository.findByAccountNumber(accountNumber)
				.map(Account::getId)
				.orElseThrow(() -> AccountNotFoundException.byNumber(accountNumber));
	}

	/** 계좌를 조회한다. readOnly: 변경 감지를 생략해 조회 트랜잭션의 부담을 줄인다. */
	@Transactional(readOnly = true)
	public Account get(Long id) {
		return accountRepository.findById(id)
				.orElseThrow(() -> new AccountNotFoundException(id));
	}

	/**
	 * 입금한다. 계좌 잠금, 잔액 증가, 원장 기록, 멱등성 키 기록을 한 트랜잭션으로 처리하므로 하나라도 실패하면 전부 취소된다.
	 * 잔액 변경은 JPA 변경 감지로 커밋할 때 UPDATE 된다.
	 *
	 * <p>같은 키로 다시 오면 잔액을 바꾸지 않고 처음 거래를 돌려준다. 키 확인은 반드시 잠금 뒤에 한다(IdempotencyService 참고).
	 */
	@Transactional
	public IdempotentResult<LedgerTransaction> deposit(Long id, long amount, IdempotencyKey key) {
		Account account = lock(id);
		String fingerprint = "DEPOSIT|account=" + id + "|amount=" + amount;
		Optional<Long> completed = idempotencyService.findCompleted(key, fingerprint);
		if (completed.isPresent()) {
			return IdempotentResult.replayed(ledgerService.getTransaction(completed.get()));
		}

		account.checkDemandTransactionsAllowed();
		account.deposit(amount);
		LedgerTransaction transaction = ledgerService.recordDeposit(id, amount, account.getBalance());
		idempotencyService.record(key, IdempotentOperation.DEPOSIT, fingerprint, transaction.getId());
		return IdempotentResult.created(transaction);
	}

	/**
	 * 출금한다. 입금과 같은 구조이며, 잠금이 특히 중요하다. 잠금이 없으면 잔액 10,000원 계좌에
	 * 7,000원 출금 두 건이 동시에 들어올 때 둘 다 잔액 확인을 통과해 버린다.
	 * 잠금 덕분에 두 번째 요청은 첫 번째가 커밋된 뒤의 잔액(3,000원)을 보고 거부된다.
	 */
	@Transactional
	public IdempotentResult<LedgerTransaction> withdraw(Long id, long amount, IdempotencyKey key) {
		Account account = lock(id);
		String fingerprint = "WITHDRAWAL|account=" + id + "|amount=" + amount;
		Optional<Long> completed = idempotencyService.findCompleted(key, fingerprint);
		if (completed.isPresent()) {
			return IdempotentResult.replayed(ledgerService.getTransaction(completed.get()));
		}

		account.checkDemandTransactionsAllowed();
		account.withdraw(amount);
		LedgerTransaction transaction = ledgerService.recordWithdrawal(id, amount, account.getBalance());
		idempotencyService.record(key, IdempotentOperation.WITHDRAWAL, fingerprint, transaction.getId());
		return IdempotentResult.created(transaction);
	}

	/**
	 * 계좌를 해지한다. 남은 잔액은 고객에게 지급(CLOSURE_PAYOUT)하고 잔액 0, CLOSED 상태가 된다.
	 *
	 * <p>잠금이 필요한 이유: 해지와 입금이 동시에 들어오면, 잠금이 없을 때 입금이 해지 확인을 통과한 뒤
	 * 해지가 잔액을 0으로 덮어써 입금한 돈이 사라질 수 있다. 잠금으로 줄을 세우면 입금이 먼저면 그 돈까지 지급되고,
	 * 해지가 먼저면 입금은 ACCOUNT_CLOSED 로 거부된다.
	 *
	 * <p>같은 키로 다시 오면 이미 해지된 계좌라도 409 가 아니라 처음 결과를 돌려준다. 그래서 키 확인을 해지 확인보다 먼저 한다.
	 */
	@Transactional
	public IdempotentResult<AccountClosure> close(Long id, IdempotencyKey key) {
		Account account = lock(id);
		String fingerprint = "CLOSURE|account=" + id;
		Optional<Long> completed = idempotencyService.findCompleted(key, fingerprint);
		if (completed.isPresent()) {
			return IdempotentResult.replayed(new AccountClosure(account, ledgerService.findClosurePayout(id).orElse(null)));
		}

		// 정기예금은 중도해지(TimeDepositService)로 끝낸다. 이자 계산 방식이 다르고, 원리금이 현금이 아니라 연결계좌로 간다.
		account.checkDemandTransactionsAllowed();
		// 가입 중인 정기예금의 연결계좌면 거부한다. 계좌를 잠근 뒤에 확인하므로, 정기예금 가입(이 계좌를 잠그고 출금)과 겹쳐도
		// 둘 중 하나만 먼저 끝난다. 가입이 먼저면 여기서 거부되고, 해지가 먼저면 가입이 ACCOUNT_CLOSED 로 거부된다.
		if (account.getStatus() == AccountStatus.ACTIVE
				&& timeDepositRepository.existsByPayoutAccountIdAndStatus(id, TimeDepositStatus.ACTIVE)) {
			throw new LinkedTimeDepositExistsException(id);
		}
		// 해지 전에 어제까지 쌓인 이자를 잔액에 넣는다. 그래야 해지 지급액에 이자까지 함께 나간다.
		// 이미 해지된 계좌면 바로 아래 close() 가 ACCOUNT_CLOSED 로 거부하므로 이자를 건드리지 않는다.
		if (account.getStatus() == AccountStatus.ACTIVE) {
			interestService.payOnClosure(account);
		}
		long payout = account.close();
		LedgerTransaction payoutTransaction = payout > 0 ? ledgerService.recordClosurePayout(id, payout) : null;
		idempotencyService.record(key, IdempotentOperation.CLOSURE, fingerprint, id);
		return IdempotentResult.created(new AccountClosure(account, payoutTransaction));
	}

	/**
	 * 거래내역을 최신순으로 size 건 조회한다. before 가 있으면 그 거래보다 오래된 거래부터 돌려준다.
	 *
	 * <p>다음 페이지가 있는지 알려고 size + 1 건을 읽는다. 한 건이 더 오면 다음 페이지가 있다는 뜻이고,
	 * 그 한 건은 버린다. 전체 건수를 세는 COUNT 쿼리를 따로 날리지 않아도 된다.
	 */
	@Transactional(readOnly = true)
	public TransactionHistoryResponse history(Long id, Long before, int size) {
		if (!accountRepository.existsById(id)) {
			throw new AccountNotFoundException(id);
		}

		List<LedgerHistoryEntry> entries = ledgerService.findHistory(id, before, size + 1);
		boolean hasNext = entries.size() > size;
		List<LedgerHistoryEntry> page = hasNext ? entries.subList(0, size) : entries;

		// 이체 상대 계좌 정보도 한 번에 모아서 조회한다.
		Set<Long> counterpartyIds = page.stream()
				.map(LedgerHistoryEntry::counterpartyAccountId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<Long, Account> counterparties = accountRepository.findAllById(counterpartyIds).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));

		List<TransactionHistoryResponse.Item> items = page.stream()
				.map(entry -> {
					Account counterparty = counterparties.get(entry.counterpartyAccountId());
					return TransactionHistoryResponse.Item.of(entry.transaction(),
							counterparty == null ? null : TransactionHistoryResponse.Counterparty.from(counterparty));
				})
				.toList();
		Long nextBefore = hasNext ? items.getLast().transactionId() : null;
		return new TransactionHistoryResponse(id, items, nextBefore);
	}

	/**
	 * 계좌를 잠그고 조회한다. SELECT ... FOR UPDATE 로, 트랜잭션이 끝날 때까지 같은 계좌의 다른 거래는 기다린다.
	 */
	private Account lock(Long id) {
		return accountRepository.findByIdForUpdate(id)
				.orElseThrow(() -> new AccountNotFoundException(id));
	}

}
