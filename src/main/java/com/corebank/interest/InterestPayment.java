package com.corebank.interest;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 이자 지급 한 건. 원장처럼 추가만 한다(모든 열 updatable = false, 앱 계정은 INSERT 권한만).
 * 원장에는 세후 금액만 고객 거래로 보이므로, 세전·세금·기간·적수는 여기에 남긴다.
 */
@Getter
@Entity
@Table(name = "interest_payment")
public class InterestPayment {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "interest_payment_seq_generator")
	@SequenceGenerator(name = "interest_payment_seq_generator", sequenceName = "interest_payment_seq", allocationSize = 1)
	private Long id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, updatable = false, length = 10)
	private InterestTrigger trigger;

	@Column(name = "period_from", nullable = false, updatable = false)
	private LocalDate periodFrom;

	@Column(name = "period_to", nullable = false, updatable = false)
	private LocalDate periodTo;

	@Column(name = "day_count", nullable = false, updatable = false)
	private int dayCount;

	@Column(name = "balance_rate_sum", nullable = false, updatable = false, precision = 38, scale = 2)
	private BigDecimal balanceRateSum;

	@Column(name = "gross_amount", nullable = false, updatable = false)
	private long grossAmount;

	@Column(name = "income_tax", nullable = false, updatable = false)
	private long incomeTax;

	@Column(name = "local_tax", nullable = false, updatable = false)
	private long localTax;

	@Column(name = "net_amount", nullable = false, updatable = false)
	private long netAmount;

	// 이자가 0원이면 원장 거래가 없어 null 이다.
	@Column(name = "ledger_transaction_id", updatable = false)
	private Long ledgerTransactionId;

	@Column(name = "paid_at", nullable = false, updatable = false)
	private LocalDateTime paidAt;

	protected InterestPayment() {
	}

	static InterestPayment of(Long accountId, InterestTrigger trigger, InterestAccruals.Unpaid unpaid,
			InterestCalculator.Interest interest, Long ledgerTransactionId) {
		InterestPayment payment = new InterestPayment();
		payment.accountId = accountId;
		payment.trigger = trigger;
		payment.periodFrom = unpaid.from();
		payment.periodTo = unpaid.to();
		payment.dayCount = unpaid.days();
		payment.balanceRateSum = unpaid.balanceRateSum();
		payment.grossAmount = interest.gross();
		payment.incomeTax = interest.incomeTax();
		payment.localTax = interest.localTax();
		payment.netAmount = interest.net();
		payment.ledgerTransactionId = ledgerTransactionId;
		payment.paidAt = DbTime.now();
		return payment;
	}

}
