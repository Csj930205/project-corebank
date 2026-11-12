package com.corebank.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Limit;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 원장 기록을 담당한다. 잔액 변경과 원장 기록이 함께 커밋되어야 하므로, 호출자의 트랜잭션 안에서만 실행되게 한다.
 */
@Service
public class LedgerService {

	private final LedgerTransactionRepository transactionRepository;
	private final LedgerEntryRepository entryRepository;

	public LedgerService(LedgerTransactionRepository transactionRepository, LedgerEntryRepository entryRepository) {
		this.transactionRepository = transactionRepository;
		this.entryRepository = entryRepository;
	}

	/** 원장 거래를 조회한다. 멱등성 키로 다시 보낸 요청에 처음 결과를 돌려줄 때 쓴다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction getTransaction(Long id) {
		return transactionRepository.findById(id)
				.orElseThrow(() -> new IllegalStateException("멱등성 기록이 가리키는 원장 거래가 없습니다. id=" + id));
	}

	/** 이체를 이루는 원장 거래를 종류별로 찾는다. 이체 후 잔액(balance_after)을 다시 돌려줄 때 쓴다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction getTransferTransaction(Long transferId, TransactionType type) {
		return transactionRepository.findByTransferId(transferId).stream()
				.filter(transaction -> transaction.getType() == type)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"이체의 원장 거래가 없습니다. transferId=" + transferId + ", type=" + type));
	}

	/**
	 * 계좌의 거래내역을 최신순으로 limit 건 조회한다. before 가 있으면 그 거래보다 오래된 것부터다.
	 *
	 * <p>id 순서가 곧 거래 순서다. 같은 계좌의 거래는 계좌 잠금으로 한 줄로 처리되고, 시퀀스는 처리 순서대로 번호를 주기 때문이다.
	 * created_at 은 같은 시각이 겹칠 수 있어 정렬 기준으로 쓰지 않는다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<LedgerHistoryEntry> findHistory(Long accountId, Long before, int limit) {
		List<LedgerTransaction> transactions = before == null
				? transactionRepository.findByAccountIdOrderByIdDesc(accountId, Limit.of(limit))
				: transactionRepository.findByAccountIdAndIdLessThanOrderByIdDesc(accountId, before, Limit.of(limit));

		// 이체 상대 계좌는 한 번의 쿼리로 모아서 찾는다. 거래마다 조회하면 N+1 쿼리가 된다.
		List<Long> transferIds = transactions.stream()
				.map(LedgerTransaction::getTransferId)
				.filter(Objects::nonNull)
				.toList();
		Map<Long, Long> counterpartyByTransfer = transferIds.isEmpty()
				? Map.of()
				: transactionRepository.findByTransferIdInAndAccountIdNot(transferIds, accountId).stream()
						.collect(Collectors.toMap(LedgerTransaction::getTransferId, LedgerTransaction::getAccountId));

		return transactions.stream()
				.map(transaction -> new LedgerHistoryEntry(transaction,
						transaction.getTransferId() == null ? null : counterpartyByTransfer.get(transaction.getTransferId())))
				.toList();
	}

	/** 입금을 기록한다. 차변 현금과 대변 고객 예금에 같은 금액을 남긴다. MANDATORY: 트랜잭션이 없으면 예외가 난다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction recordDeposit(Long accountId, long amount, long balanceAfter) {
		LedgerTransaction transaction = transactionRepository.save(
				LedgerTransaction.deposit(accountId, amount, balanceAfter));
		entryRepository.saveAll(List.of(
				LedgerEntry.cashDebit(transaction.getId(), amount),
				LedgerEntry.customerDepositCredit(transaction.getId(), accountId, amount)));
		return transaction;
	}

	/** 출금을 기록한다. 입금과 방향만 반대로, 차변 고객 예금과 대변 현금에 같은 금액을 남긴다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction recordWithdrawal(Long accountId, long amount, long balanceAfter) {
		LedgerTransaction transaction = transactionRepository.save(
				LedgerTransaction.withdrawal(accountId, amount, balanceAfter));
		entryRepository.saveAll(List.of(
				LedgerEntry.customerDepositDebit(transaction.getId(), accountId, amount),
				LedgerEntry.cashCredit(transaction.getId(), amount)));
		return transaction;
	}

	/**
	 * 해지 지급을 기록한다. 고객에게 현금으로 돌려주므로 분개는 출금과 같다(차변 고객 예금 / 대변 현금).
	 * 잔액이 0인 계좌를 해지할 때는 움직인 돈이 없으므로 호출하지 않는다(원장 금액은 1원 이상이어야 한다).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction recordClosurePayout(Long accountId, long amount) {
		LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.closurePayout(accountId, amount));
		entryRepository.saveAll(List.of(
				LedgerEntry.customerDepositDebit(transaction.getId(), accountId, amount),
				LedgerEntry.cashCredit(transaction.getId(), amount)));
		return transaction;
	}

	/** 계좌의 해지 지급 거래. 잔액 0으로 해지했으면 비어 있다. 해지 요청을 다시 보냈을 때 처음 결과를 만들 때 쓴다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<LedgerTransaction> findClosurePayout(Long accountId) {
		return transactionRepository.findByAccountIdAndType(accountId, TransactionType.CLOSURE_PAYOUT);
	}

	/**
	 * 정정할 원장 거래 묶음. 입출금은 그 거래 하나, 이체는 출금 쪽과 입금 쪽 두 거래다(반쪽만 되돌리면 이체 가계정이 어긋난다).
	 * 출금 쪽이 먼저 온다.
	 *
	 * @return 없는 거래면 빈 목록
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<LedgerTransaction> findCorrectionTargets(Long transactionId) {
		Optional<LedgerTransaction> found = transactionRepository.findById(transactionId);
		if (found.isEmpty() || found.get().getTransferId() == null) {
			return found.map(List::of).orElse(List.of());
		}
		return transactionRepository.findByTransferId(found.get().getTransferId()).stream()
				.sorted(Comparator.comparing(transaction -> transaction.getType() != TransactionType.TRANSFER_OUT))
				.toList();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean isReversed(Long transactionId) {
		return transactionRepository.existsByReversalOfId(transactionId);
	}

	/** 여러 이체의 원장 거래(출금 쪽, 입금 쪽)를 한 번의 쿼리로 읽는다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<LedgerTransaction> findTransferLegs(Collection<Long> transferIds) {
		return transferIds.isEmpty() ? List.of() : transactionRepository.findByTransferIdIn(transferIds);
	}

	/** 여러 거래의 역분개를 한 번의 쿼리로 읽는다. 되돌린 원래 거래 id → 역분개 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<Long, LedgerTransaction> findReversals(Collection<Long> transactionIds) {
		return transactionIds.isEmpty() ? Map.of() : transactionRepository.findByReversalOfIdIn(transactionIds).stream()
				.collect(Collectors.toMap(LedgerTransaction::getReversalOfId, Function.identity()));
	}

	/**
	 * 역분개를 기록한다. 원래 거래의 분개를 모두 읽어 차변·대변을 바꿔 다시 쓴다. 입금·출금·이체 어느 쪽이든 같은 규칙이라
	 * 거래 종류마다 분개를 따로 정하지 않는다. 예: 입금(차변 현금 / 대변 고객 예금) → 역분개(차변 고객 예금 / 대변 현금).
	 *
	 * <p>계좌 잔액은 호출하는 쪽이 잠금을 잡고 먼저 바꾼다. 여기서는 그 결과(balanceAfter)를 기록만 한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction recordReversal(LedgerTransaction original, long balanceAfter) {
		LedgerTransaction reversal = transactionRepository.save(LedgerTransaction.reversalOf(original, balanceAfter));
		entryRepository.saveAll(entryRepository.findByTransactionIdOrderById(original.getId()).stream()
				.map(entry -> entry.reversedFor(reversal.getId()))
				.toList());
		return reversal;
	}

	/**
	 * 이자 지급을 기록한다. 세 줄 분개로 차변 합과 대변 합이 같다(세전 = 세후 + 세금).
	 * <pre>
	 * 차변 이자비용       gross
	 *       대변 고객 예금        net   (고객 잔액 증가)
	 *       대변 이자세 예수금    tax   (세금이 0원이면 이 줄은 없다. 분개 금액은 1원 이상이어야 한다)
	 * </pre>
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerTransaction recordInterest(Long accountId, long gross, long tax, long balanceAfter) {
		long net = gross - tax;
		LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.interest(accountId, net, balanceAfter));
		List<LedgerEntry> entries = new java.util.ArrayList<>(List.of(
				LedgerEntry.interestExpenseDebit(transaction.getId(), gross),
				LedgerEntry.customerDepositCredit(transaction.getId(), accountId, net)));
		if (tax > 0) {
			entries.add(LedgerEntry.interestTaxCredit(transaction.getId(), tax));
		}
		entryRepository.saveAll(entries);
		return transaction;
	}

	/**
	 * 이체를 기록한다. 계좌별로 거래를 하나씩 남기고, 두 거래 사이는 이체 가계정으로 잇는다.
	 * <pre>
	 * 출금 쪽 거래: 차변 고객 예금(보내는 계좌) / 대변 이체 가계정
	 * 입금 쪽 거래: 차변 이체 가계정           / 대변 고객 예금(받는 계좌)
	 * </pre>
	 * 거래마다 차변과 대변이 맞고, 가계정은 대변과 차변이 같은 금액이라 합계가 0으로 돌아온다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordTransfer(Long transferId, Long fromAccountId, Long toAccountId, long amount,
			long fromBalanceAfter, long toBalanceAfter) {
		LedgerTransaction out = transactionRepository.save(
				LedgerTransaction.transferOut(fromAccountId, transferId, amount, fromBalanceAfter));
		LedgerTransaction in = transactionRepository.save(
				LedgerTransaction.transferIn(toAccountId, transferId, amount, toBalanceAfter));
		entryRepository.saveAll(List.of(
				LedgerEntry.customerDepositDebit(out.getId(), fromAccountId, amount),
				LedgerEntry.clearingCredit(out.getId(), amount),
				LedgerEntry.clearingDebit(in.getId(), amount),
				LedgerEntry.customerDepositCredit(in.getId(), toAccountId, amount)));
	}

}
