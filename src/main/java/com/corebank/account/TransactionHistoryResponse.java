package com.corebank.account;

import com.corebank.ledger.LedgerTransaction;
import com.corebank.ledger.TransactionType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 거래내역 조회 결과.
 *
 * @param transactions 최신순 거래 목록
 * @param nextBefore   다음 페이지를 받을 때 before 에 넣을 값. 더 이상 거래가 없으면 null
 */
public record TransactionHistoryResponse(
		Long accountId,
		List<Item> transactions,
		Long nextBefore) {

	/** 들어온 돈인지 나간 돈인지. 화면에서 +/- 표시와 색을 정할 때 쓴다. */
	public enum Direction {
		IN, OUT;

		static Direction of(TransactionType type) {
			return switch (type) {
				case DEPOSIT, TRANSFER_IN -> IN;
				case WITHDRAWAL, TRANSFER_OUT, CLOSURE_PAYOUT -> OUT;
			};
		}
	}

	/**
	 * 거래 한 줄.
	 *
	 * @param amount       거래 금액. 항상 양수이고, 방향은 direction 으로 구분한다.
	 * @param balanceAfter 거래 직후 잔액
	 * @param counterparty 이체 상대 계좌. 입출금은 null
	 */
	public record Item(
			Long transactionId,
			TransactionType type,
			Direction direction,
			long amount,
			long balanceAfter,
			Long transferId,
			Counterparty counterparty,
			LocalDateTime createdAt) {

		static Item of(LedgerTransaction transaction, Counterparty counterparty) {
			return new Item(
					transaction.getId(),
					transaction.getType(),
					Direction.of(transaction.getType()),
					transaction.getAmount(),
					transaction.getBalanceAfter(),
					transaction.getTransferId(),
					counterparty,
					transaction.getCreatedAt());
		}
	}

	/** 이체 상대 계좌. 은행 앱의 "받는 분 / 보낸 분" 표시용이다. */
	public record Counterparty(Long accountId, String accountNumber, String ownerName) {

		static Counterparty from(Account account) {
			return new Counterparty(account.getId(), account.getAccountNumber(), account.getOwnerName());
		}
	}

}
