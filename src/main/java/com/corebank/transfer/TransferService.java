package com.corebank.transfer;

import com.corebank.account.Account;
import com.corebank.account.AccountNotFoundException;
import com.corebank.account.AccountRepository;
import com.corebank.idempotency.IdempotencyKey;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.idempotency.IdempotentOperation;
import com.corebank.idempotency.IdempotentResult;
import com.corebank.ledger.LedgerService;
import com.corebank.ledger.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 계좌 간 이체. 출금, 입금, 원장 기록, 멱등성 키 기록을 한 트랜잭션으로 처리하므로 중간에 실패하면 전부 취소된다.
 */
@Service
public class TransferService {

	private final AccountRepository accountRepository;
	private final TransferRepository transferRepository;
	private final LedgerService ledgerService;
	private final IdempotencyService idempotencyService;

	public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
			LedgerService ledgerService, IdempotencyService idempotencyService) {
		this.accountRepository = accountRepository;
		this.transferRepository = transferRepository;
		this.ledgerService = ledgerService;
		this.idempotencyService = idempotencyService;
	}

	@Transactional
	public IdempotentResult<TransferResult> transfer(Long fromId, Long toId, long amount, IdempotencyKey key) {
		if (fromId.equals(toId)) {
			throw new SameAccountTransferException(fromId);
		}

		/*
		 * 데드락 방지: 두 계좌를 항상 id 가 작은 쪽부터 잠근다.
		 * 보내는 계좌부터 잠그면, A→B 이체와 B→A 이체가 동시에 올 때 각자 첫 번째 계좌를 잡은 채
		 * 상대가 쥔 계좌를 기다리게 된다(데드락). Oracle 은 이를 감지해 한쪽을 ORA-00060 으로 실패시킨다.
		 * 잠그는 순서를 이체 방향과 상관없이 고정하면 이런 순환 대기가 생기지 않는다.
		 */
		Account first = lock(Math.min(fromId, toId));
		Account second = lock(Math.max(fromId, toId));
		Account from = first.getId().equals(fromId) ? first : second;
		Account to = from == first ? second : first;

		// 두 계좌를 모두 잠근 뒤에 키를 확인한다. 같은 이체가 동시에 오면 두 번째는 잠금에서 기다렸다가 저장된 결과를 받는다.
		String fingerprint = "TRANSFER|from=" + fromId + "|to=" + toId + "|amount=" + amount;
		Optional<Long> completed = idempotencyService.findCompleted(key, fingerprint);
		if (completed.isPresent()) {
			return IdempotentResult.replayed(load(completed.get()));
		}

		// 정기예금 계좌는 보내는 쪽도 받는 쪽도 안 된다(원금이 바뀌면 약정한 이자 계산이 틀어진다).
		from.checkDemandTransactionsAllowed();
		to.checkDemandTransactionsAllowed();
		// 출금 쪽에서 해지·잔액 부족을, 입금 쪽에서 해지·한도 초과를 검사한다.
		// 입금 쪽에서 실패해도 예외로 트랜잭션이 롤백되므로 이미 차감한 출금 계좌 잔액도 되돌아간다.
		from.withdraw(amount);
		to.deposit(amount);

		Transfer transfer = transferRepository.save(Transfer.of(fromId, toId, amount));
		ledgerService.recordTransfer(transfer.getId(), fromId, toId, amount, from.getBalance(), to.getBalance());
		idempotencyService.record(key, IdempotentOperation.TRANSFER, fingerprint, transfer.getId());
		return IdempotentResult.created(new TransferResult(transfer, from.getBalance(), to.getBalance()));
	}

	/**
	 * 은행이 계약에 따라 옮기는 돈을 이체로 기록한다(정기예금 가입 원금: 연결계좌 → 정기예금, 해지 원리금: 정기예금 → 연결계좌).
	 * 고객 이체와 같은 이체 기록·원장 분개를 남겨, 거래내역에 상대 계좌가 보이고 일 마감 대사도 그대로 맞는다.
	 *
	 * <p>호출하는 쪽이 두 계좌를 잠그고 잔액을 이미 바꿨다(정기예금 해지는 withdraw 가 아니라 close 로 잔액이 0 이 되므로,
	 * 잔액을 바꾸는 방법이 경우마다 다르다). 여기서는 기록만 한다. 멱등성 키도 호출하는 쪽 작업 단위로 확인한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Transfer recordInternal(Account from, Account to, long amount) {
		Transfer transfer = transferRepository.save(Transfer.of(from.getId(), to.getId(), amount));
		ledgerService.recordTransfer(transfer.getId(), from.getId(), to.getId(), amount, from.getBalance(),
				to.getBalance());
		return transfer;
	}

	/** 처음 처리한 이체 결과를 다시 만든다. 잔액은 지금 잔액이 아니라 그때 원장에 남긴 이체 후 잔액이다. */
	private TransferResult load(Long transferId) {
		Transfer transfer = transferRepository.findById(transferId)
				.orElseThrow(() -> new IllegalStateException("멱등성 기록이 가리키는 이체가 없습니다. id=" + transferId));
		long fromBalanceAfter = ledgerService.getTransferTransaction(transferId, TransactionType.TRANSFER_OUT).getBalanceAfter();
		long toBalanceAfter = ledgerService.getTransferTransaction(transferId, TransactionType.TRANSFER_IN).getBalanceAfter();
		return new TransferResult(transfer, fromBalanceAfter, toBalanceAfter);
	}

	private Account lock(Long id) {
		return accountRepository.findByIdForUpdate(id)
				.orElseThrow(() -> new AccountNotFoundException(id));
	}

}
