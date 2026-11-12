package com.corebank.correction;

import com.corebank.account.Account;
import com.corebank.ledger.LedgerTransaction;
import com.corebank.ledger.TransactionType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정정 요청 응답. 결정하는 관리자가 무엇을 되돌리는지 한눈에 보도록 대상 거래 정보를 함께 담는다.
 *
 * @param transactionId          대상 거래. 이체는 출금 쪽 거래
 * @param accountNumber          대상 거래의 계좌(이체는 보낸 계좌)
 * @param counterpartAccountNumber 이체의 받은 계좌. 입출금은 null
 * @param reversalTransactionIds 승인으로 생긴 역분개 거래 id. 이체는 2개, 승인 전·반려는 빈 목록
 */
public record CorrectionView(
		Long id,
		CorrectionStatus status,
		String reason,
		String requestedBy,
		LocalDateTime requestedAt,
		String decidedBy,
		LocalDateTime decidedAt,
		String decisionComment,
		Long transactionId,
		TransactionType transactionType,
		long amount,
		LocalDateTime transactionAt,
		Long transferId,
		Long accountId,
		String accountNumber,
		String ownerName,
		String counterpartAccountNumber,
		List<Long> reversalTransactionIds) {

	static CorrectionView of(CorrectionRequest request, Account account, Account counterpart, List<Long> reversalIds) {
		LedgerTransaction transaction = request.getTransaction();
		return new CorrectionView(request.getId(), request.getStatus(), request.getReason(),
				request.getRequestedBy(), request.getRequestedAt(), request.getDecidedBy(), request.getDecidedAt(),
				request.getDecisionComment(),
				transaction.getId(), transaction.getType(), transaction.getAmount(), transaction.getCreatedAt(),
				transaction.getTransferId(), transaction.getAccountId(),
				account == null ? null : account.getAccountNumber(), account == null ? null : account.getOwnerName(),
				counterpart == null ? null : counterpart.getAccountNumber(), reversalIds);
	}

}
