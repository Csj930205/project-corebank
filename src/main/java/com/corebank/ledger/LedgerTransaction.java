package com.corebank.ledger;

import com.corebank.common.DbTime;
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

import java.time.LocalDateTime;

/**
 * 원장 거래 한 건. 기록은 수정하지 않으므로 모든 컬럼을 updatable = false 로 두고 setter 도 만들지 않는다.
 *
 * <p>스프링의 @Transactional 과 헷갈리지 않도록 이름을 LedgerTransaction 으로 했다.
 */
@Getter
@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_transaction_seq_generator")
	@SequenceGenerator(name = "ledger_transaction_seq_generator", sequenceName = "ledger_transaction_seq", allocationSize = 1)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "transaction_type", nullable = false, updatable = false, length = 20)
	private TransactionType type;

	// 원장은 계좌 ID 값만 가진다. JPA 연관관계를 두지 않아 계좌 엔티티와 결합하지 않는다. 무결성은 DB 외래키가 지킨다.
	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	// 이체 거래일 때만 값이 있다. 같은 이체의 출금 쪽과 입금 쪽 거래가 같은 번호를 가진다.
	@Column(name = "transfer_id", updatable = false)
	private Long transferId;

	// 역분개 거래일 때만 값이 있다. 되돌린 원래 거래의 id 다. 한 거래는 한 번만 되돌릴 수 있다(ux_ledger_transaction_reversal).
	@Column(name = "reversal_of_id", updatable = false)
	private Long reversalOfId;

	@Column(nullable = false, updatable = false)
	private long amount;

	@Column(name = "balance_after", nullable = false, updatable = false)
	private long balanceAfter;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected LedgerTransaction() {
	}

	private LedgerTransaction(TransactionType type, Long accountId, Long transferId, long amount, long balanceAfter) {
		this.type = type;
		this.accountId = accountId;
		this.transferId = transferId;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.createdAt = DbTime.now();
	}

	static LedgerTransaction deposit(Long accountId, long amount, long balanceAfter) {
		return new LedgerTransaction(TransactionType.DEPOSIT, accountId, null, amount, balanceAfter);
	}

	static LedgerTransaction withdrawal(Long accountId, long amount, long balanceAfter) {
		return new LedgerTransaction(TransactionType.WITHDRAWAL, accountId, null, amount, balanceAfter);
	}

	/** 해지 지급. 잔액 전부가 나가므로 거래 후 잔액은 항상 0이다. */
	static LedgerTransaction closurePayout(Long accountId, long amount) {
		return new LedgerTransaction(TransactionType.CLOSURE_PAYOUT, accountId, null, amount, 0);
	}

	static LedgerTransaction transferOut(Long accountId, Long transferId, long amount, long balanceAfter) {
		return new LedgerTransaction(TransactionType.TRANSFER_OUT, accountId, transferId, amount, balanceAfter);
	}

	/**
	 * original 을 되돌리는 역분개. 금액은 같고 잔액 방향만 반대다. 이체 번호는 넣지 않는다(V14 주석 참고).
	 *
	 * @param balanceAfter 역분개를 반영한 뒤 계좌 잔액
	 */
	static LedgerTransaction reversalOf(LedgerTransaction original, long balanceAfter) {
		TransactionType type = original.type.increasesBalance() ? TransactionType.REVERSAL_OUT : TransactionType.REVERSAL_IN;
		LedgerTransaction reversal = new LedgerTransaction(type, original.accountId, null, original.amount, balanceAfter);
		reversal.reversalOfId = original.id;
		return reversal;
	}

	/** 이자 지급. amount 는 고객 잔액에 더해진 세후 금액이다(세전·세금은 분개와 interest_payment 에 있다). */
	static LedgerTransaction interest(Long accountId, long netAmount, long balanceAfter) {
		return new LedgerTransaction(TransactionType.INTEREST, accountId, null, netAmount, balanceAfter);
	}

	static LedgerTransaction transferIn(Long accountId, Long transferId, long amount, long balanceAfter) {
		return new LedgerTransaction(TransactionType.TRANSFER_IN, accountId, transferId, amount, balanceAfter);
	}

}
