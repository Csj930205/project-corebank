package com.corebank.correction;

import com.corebank.account.Account;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountRepository;
import com.corebank.ledger.LedgerService;
import com.corebank.ledger.LedgerTransaction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 거래 정정. 요청 → (다른 관리자의) 승인 → 역분개 기록.
 *
 * <p>두 종류의 락을 쓴다. 왜 다른지가 이 클래스의 핵심이다.
 * <ul>
 *   <li><b>계좌: 비관적 락</b>(SELECT ... FOR UPDATE). 입출금·이체와 같은 계좌를 두고 자주 부딪히고, 부딪혔을 때
 *       "실패했으니 다시 하세요"가 아니라 줄 서서 차례로 처리돼야 한다(잔액은 정확해야 한다).</li>
 *   <li><b>정정 요청: 낙관적 락</b>(@Version). 같은 요청을 두 관리자가 동시에 결정하는 일은 드물고, 부딪히면 나중 사람에게
 *       "이미 처리됐습니다"라고 알려 주면 끝이다. 드문 충돌 때문에 매번 행을 잠글 필요가 없다.</li>
 * </ul>
 */
@Service
public class CorrectionService {

	private final CorrectionRequestRepository repository;
	private final LedgerService ledgerService;
	private final AccountRepository accountRepository;

	public CorrectionService(CorrectionRequestRepository repository, LedgerService ledgerService,
			AccountRepository accountRepository) {
		this.repository = repository;
		this.ledgerService = ledgerService;
		this.accountRepository = accountRepository;
	}

	/**
	 * 정정을 요청한다. 원장은 아직 바뀌지 않는다. 이체는 어느 쪽 거래 id 로 요청해도 이체 전체(두 거래)를 되돌린다.
	 *
	 * <p>같은 거래에 열린 요청이 있는지는 미리 조회하지 않고 DB 유니크 인덱스(ux_correction_request_open)로 확인한다.
	 * 조회 후 저장하면 두 관리자가 동시에 요청할 때 둘 다 조회를 통과한다(고객 가입의 아이디 중복과 같은 이유).
	 *
	 * @throws TransactionNotFoundException 없는 거래
	 * @throws NotCorrectableException      해지 지급, 역분개
	 * @throws CorrectionConflictException  이미 정정됐거나, 승인을 기다리는 요청이 있음
	 */
	@Transactional
	public CorrectionView request(Long transactionId, String reason, String admin) {
		List<LedgerTransaction> targets = ledgerService.findCorrectionTargets(transactionId);
		if (targets.isEmpty()) {
			throw new TransactionNotFoundException(transactionId);
		}
		// 이체면 출금 쪽 거래를 대표로 둔다. 어느 쪽으로 요청해도 같은 요청이 되어 유니크 인덱스가 중복을 잡는다.
		LedgerTransaction target = targets.getFirst();
		if (!target.getType().isCorrectable()) {
			throw new NotCorrectableException(transactionId, target.getType());
		}
		if (ledgerService.isReversed(target.getId())) {
			throw CorrectionConflictException.alreadyReversed(transactionId);
		}
		try {
			return view(repository.saveAndFlush(CorrectionRequest.of(target, reason.strip(), admin)));
		} catch (DataIntegrityViolationException e) {
			throw CorrectionConflictException.alreadyRequested(transactionId);
		}
	}

	/**
	 * 승인하고 역분개를 기록한다. 요청 상태 변경, 계좌 잔액 변경, 원장 기록이 한 트랜잭션이라 하나라도 실패하면 모두 취소되고
	 * 요청은 REQUESTED 로 남는다(예: 고객이 이미 돈을 써서 잔액이 부족하면 422, 계좌가 해지됐으면 409).
	 *
	 * <p>멱등성 키를 받지 않는다. 같은 승인을 다시 보내면 상태가 이미 APPROVED 라 409 로 끝나고, 역분개가 두 번 들어갈 수 없다
	 * (ux_ledger_transaction_reversal 이 마지막으로 한 번 더 막는다).
	 *
	 * @throws SelfApprovalException       요청한 사람이 승인
	 * @throws CorrectionConflictException 이미 결정된 요청(동시 결정 포함), 그 사이 정정된 거래
	 */
	@Transactional
	public CorrectionView approve(Long id, String admin, String comment) {
		CorrectionRequest request = find(id);
		request.approve(admin, comment);
		flushDecision(request);

		List<LedgerTransaction> originals = ledgerService.findCorrectionTargets(request.getTransaction().getId());
		Map<Long, Account> accounts = lockAccounts(originals);
		for (LedgerTransaction original : originals) {
			// 계좌 잠금 뒤에 확인해야 동시에 커밋된 역분개까지 보인다.
			if (ledgerService.isReversed(original.getId())) {
				throw CorrectionConflictException.alreadyReversed(original.getId());
			}
			Account account = accounts.get(original.getAccountId());
			// 원래 거래와 반대로 잔액을 움직인다. Account 의 규칙(해지 계좌 거부, 잔액 부족 거부, 한도)을 그대로 쓴다.
			if (original.getType().increasesBalance()) {
				account.withdraw(original.getAmount());
			} else {
				account.deposit(original.getAmount());
			}
			ledgerService.recordReversal(original, account.getBalance());
		}
		return view(request);
	}

	/**
	 * 반려한다. 원장은 그대로다.
	 *
	 * @throws SelfApprovalException       요청한 사람이 반려
	 * @throws CorrectionConflictException 이미 결정된 요청(동시 결정 포함)
	 */
	@Transactional
	public CorrectionView reject(Long id, String admin, String comment) {
		CorrectionRequest request = find(id);
		request.reject(admin, comment);
		flushDecision(request);
		return view(request);
	}

	@Transactional(readOnly = true)
	public CorrectionView get(Long id) {
		return view(find(id));
	}

	@Transactional(readOnly = true)
	public Page<CorrectionView> search(CorrectionStatus status, int page, int size) {
		Page<CorrectionRequest> requests = repository.search(status, PageRequest.of(page, size));
		return new PageImpl<>(views(requests.getContent()), requests.getPageable(), requests.getTotalElements());
	}

	private CorrectionRequest find(Long id) {
		return repository.findWithTransaction(id).orElseThrow(() -> new CorrectionNotFoundException(id));
	}

	/**
	 * 결정(UPDATE)을 지금 DB 에 보낸다. 보통은 커밋할 때 변경 감지로 나가지만, 여기서 바로 보내면
	 * <ul>
	 *   <li>동시에 결정한 다른 관리자가 있으면 version 이 달라 0건이 바뀌고, 역분개를 쓰기 전에 실패한다.</li>
	 *   <li>그 예외를 이 자리에서 잡아 409 로 바꿀 수 있다. 커밋 때 나면 서비스 밖(프록시)에서 나서 잡을 곳이 없다.</li>
	 * </ul>
	 * 상대가 아직 커밋하지 않았으면 Oracle 이 행 잠금에서 기다렸다가, 상대가 커밋한 뒤 바뀐 version 을 보고 0건으로 끝낸다.
	 */
	private void flushDecision(CorrectionRequest request) {
		try {
			repository.saveAndFlush(request);
		} catch (ObjectOptimisticLockingFailureException e) {
			throw CorrectionConflictException.alreadyDecided(request.getId());
		}
	}

	/** 관련 계좌를 id 가 작은 것부터 잠근다. 이체와 같은 순서라 이체·정정이 서로 데드락에 빠지지 않는다. */
	private Map<Long, Account> lockAccounts(List<LedgerTransaction> transactions) {
		Map<Long, Account> accounts = new HashMap<>();
		transactions.stream().map(LedgerTransaction::getAccountId).distinct().sorted().forEach(accountId ->
				accounts.put(accountId, accountRepository.findByIdForUpdate(accountId)
						.orElseThrow(() -> new AccountNotFoundException(accountId))));
		return accounts;
	}

	private CorrectionView view(CorrectionRequest request) {
		return views(List.of(request)).getFirst();
	}

	/**
	 * 화면에 필요한 값(이체 상대 계좌, 계좌번호, 역분개 id)을 요청 수와 상관없이 쿼리 3번으로 모은다.
	 * 요청마다 조회하면 20건 목록에 60번 넘는 쿼리가 나간다(N+1). 거래내역 조회(LedgerService.findHistory)와 같은 방식이다.
	 */
	private List<CorrectionView> views(List<CorrectionRequest> requests) {
		List<LedgerTransaction> targets = requests.stream().map(CorrectionRequest::getTransaction).toList();
		List<Long> transferIds = targets.stream().map(LedgerTransaction::getTransferId).filter(Objects::nonNull).toList();

		// 1) 이체의 두 거래
		Map<Long, LedgerTransaction> legs = new LinkedHashMap<>();
		Stream.concat(targets.stream(), ledgerService.findTransferLegs(transferIds).stream())
				.forEach(transaction -> legs.putIfAbsent(transaction.getId(), transaction));
		// 2) 역분개
		Map<Long, LedgerTransaction> reversals = ledgerService.findReversals(legs.keySet());
		// 3) 계좌
		Map<Long, Account> accounts = accountRepository.findAllById(
						legs.values().stream().map(LedgerTransaction::getAccountId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));

		List<CorrectionView> views = new ArrayList<>(requests.size());
		for (CorrectionRequest request : requests) {
			LedgerTransaction target = request.getTransaction();
			List<LedgerTransaction> group = target.getTransferId() == null
					? List.of(target)
					: legs.values().stream().filter(leg -> target.getTransferId().equals(leg.getTransferId())).toList();
			LedgerTransaction counterpart = group.stream().filter(leg -> !leg.getId().equals(target.getId()))
					.findFirst().orElse(null);
			List<Long> reversalIds = group.stream().map(leg -> reversals.get(leg.getId()))
					.filter(Objects::nonNull).map(LedgerTransaction::getId).toList();
			views.add(CorrectionView.of(request, accounts.get(target.getAccountId()),
					counterpart == null ? null : accounts.get(counterpart.getAccountId()), reversalIds));
		}
		return views;
	}

}
