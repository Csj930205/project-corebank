package com.corebank.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 거래를 구성하는 분개 한 줄. 한 거래의 차변 합과 대변 합은 항상 같아야 한다.
 */
@Getter
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_seq_generator")
	@SequenceGenerator(name = "ledger_entry_seq_generator", sequenceName = "ledger_entry_seq", allocationSize = 1)
	private Long id;

	@Column(name = "transaction_id", nullable = false, updatable = false)
	private Long transactionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "ledger_account", nullable = false, updatable = false, length = 20)
	private LedgerAccount ledgerAccount;

	// 고객 예금 분개에만 값이 있고, 은행 현금 분개는 null 이다.
	@Column(name = "account_id", updatable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_side", nullable = false, updatable = false, length = 6)
	private EntrySide side;

	@Column(nullable = false, updatable = false)
	private long amount;

	protected LedgerEntry() {
	}

	private LedgerEntry(Long transactionId, LedgerAccount ledgerAccount, Long accountId, EntrySide side, long amount) {
		this.transactionId = transactionId;
		this.ledgerAccount = ledgerAccount;
		this.accountId = accountId;
		this.side = side;
		this.amount = amount;
	}

	/** 은행 현금 증가: 자산이므로 차변에 기록한다. */
	static LedgerEntry cashDebit(Long transactionId, long amount) {
		return new LedgerEntry(transactionId, LedgerAccount.CASH, null, EntrySide.DEBIT, amount);
	}

	/** 고객 예금 증가: 은행의 부채이므로 대변에 기록한다. */
	static LedgerEntry customerDepositCredit(Long transactionId, Long accountId, long amount) {
		return new LedgerEntry(transactionId, LedgerAccount.CUSTOMER_DEPOSIT, accountId, EntrySide.CREDIT, amount);
	}

	/** 은행 현금 감소: 자산이 줄어들므로 대변에 기록한다. */
	static LedgerEntry cashCredit(Long transactionId, long amount) {
		return new LedgerEntry(transactionId, LedgerAccount.CASH, null, EntrySide.CREDIT, amount);
	}

	/** 이체 출금 쪽: 보낸 돈이 잠시 가계정에 머문다. */
	static LedgerEntry clearingCredit(Long transactionId, long amount) {
		return new LedgerEntry(transactionId, LedgerAccount.TRANSFER_CLEARING, null, EntrySide.CREDIT, amount);
	}

	/** 이체 입금 쪽: 가계정에 머물던 돈이 받는 계좌로 나간다. */
	static LedgerEntry clearingDebit(Long transactionId, long amount) {
		return new LedgerEntry(transactionId, LedgerAccount.TRANSFER_CLEARING, null, EntrySide.DEBIT, amount);
	}

	/** 고객 예금 감소: 은행의 부채가 줄어들므로 차변에 기록한다. */
	static LedgerEntry customerDepositDebit(Long transactionId, Long accountId, long amount) {
		return new LedgerEntry(transactionId, LedgerAccount.CUSTOMER_DEPOSIT, accountId, EntrySide.DEBIT, amount);
	}

}
