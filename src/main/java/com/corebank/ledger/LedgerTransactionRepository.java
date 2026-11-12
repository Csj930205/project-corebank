package com.corebank.ledger;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

	/** 이체 한 건을 이루는 원장 거래(TRANSFER_OUT, TRANSFER_IN). */
	List<LedgerTransaction> findByTransferId(Long transferId);

	/** 계좌의 특정 종류 거래 한 건. 해지 지급(CLOSURE_PAYOUT)처럼 계좌당 하나뿐인 거래를 찾을 때 쓴다. */
	Optional<LedgerTransaction> findByAccountIdAndType(Long accountId, TransactionType type);

	/** 거래내역 첫 페이지: 계좌의 최신 거래부터 limit 건. */
	List<LedgerTransaction> findByAccountIdOrderByIdDesc(Long accountId, Limit limit);

	/** 거래내역 다음 페이지: before 보다 오래된 거래부터 limit 건. (account_id, id) 인덱스를 그대로 탄다. */
	List<LedgerTransaction> findByAccountIdAndIdLessThanOrderByIdDesc(Long accountId, Long before, Limit limit);

	/** 여러 이체의 원장 거래를 한 번에. 정정 요청 목록에서 이체의 두 거래를 모을 때 쓴다. */
	List<LedgerTransaction> findByTransferIdIn(Collection<Long> transferIds);

	/** 이 거래를 되돌린 역분개가 있는지. */
	boolean existsByReversalOfId(Long reversalOfId);

	/** 여러 거래의 역분개를 한 번에. */
	List<LedgerTransaction> findByReversalOfIdIn(Collection<Long> reversalOfIds);

	/** 이체 상대 쪽 거래. 같은 이체 번호를 가진 거래 중 이 계좌가 아닌 쪽이다. */
	List<LedgerTransaction> findByTransferIdInAndAccountIdNot(Collection<Long> transferIds, Long accountId);

}
