package com.corebank.timedeposit;

import com.corebank.account.Account;
import com.corebank.account.AccountClosedException;
import com.corebank.common.DbTime;
import com.corebank.interest.InterestTrigger;
import com.corebank.product.TimeDepositProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정기예금 계약. 계좌(Account) 하나에 계약 하나다.
 *
 * <p><b>@MapsId (기본키 공유)</b>: 이 테이블의 기본키 account_id 가 곧 계좌의 기본키다. 따로 시퀀스를 두고 account_id 를
 * 외래키 열로 하나 더 두면 "계좌 하나에 계약이 둘" 인 행을 막으려고 유니크 제약을 또 걸어야 한다. 기본키를 공유하면 1:1 이 구조로
 * 보장된다. @MapsId 는 "@Id 값을 이 연관관계(account)의 id 에서 가져와라" 라는 뜻이라, 저장할 때 accountId 를 직접 넣지 않는다.
 *
 * <p><b>계좌를 상속으로 나누지 않은 이유</b>: 정기예금도 잔액·원장·잠금·소유자 확인·해지 상태는 입출금 계좌와 똑같다. 계좌를
 * 상속 계층으로 만들면 이 공통 코드가 모두 계층을 알아야 한다. 계좌는 그대로 두고, 정기예금에만 있는 약정(원금·이율·만기·연결계좌)을
 * 1:1 로 붙였다. 상속은 차이가 "상품 정의" 에 있는 상품 쪽에만 썼다(DepositProduct).
 *
 * <p>약정 열은 모두 updatable = false 다. 해지할 때 바뀌는 열(상태·적용 이율·지급·이체·해지 시각)만 앱 계정에 UPDATE 권한이 있다.
 */
@Getter
@Entity
@Table(name = "time_deposit")
public class TimeDeposit {

	@Id
	@Column(name = "account_id")
	private Long accountId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id")
	private Account account;

	/** 연관관계 타입이 자식 클래스(TimeDepositProduct)라 입출금 상품을 가리킬 수 없다(코드에서). DB 는 ck 제약이 없어 서비스가 확인한다 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	private TimeDepositProduct product;

	@Column(nullable = false, updatable = false)
	private long principal;

	// 약정이율(%). 가입할 때 상품 금리를 복사한다. 상품 금리가 나중에 바뀌어도 이 값은 그대로다.
	@Column(name = "annual_rate", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal annualRate;

	@Column(name = "term_months", nullable = false, updatable = false)
	private int termMonths;

	@Column(name = "start_date", nullable = false, updatable = false)
	private LocalDate startDate;

	@Column(name = "maturity_date", nullable = false, updatable = false)
	private LocalDate maturityDate;

	// 연결계좌. 값만 둔다(계좌 엔티티를 함께 읽을 일은 해지 때뿐이고, 그때는 잠금 조회로 따로 읽는다).
	@Column(name = "payout_account_id", nullable = false, updatable = false)
	private Long payoutAccountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private TimeDepositStatus status;

	@Column(name = "applied_rate", precision = 5, scale = 2)
	private BigDecimal appliedRate;

	@Column(name = "interest_payment_id")
	private Long interestPaymentId;

	@Column(name = "payout_transfer_id")
	private Long payoutTransferId;

	@Column(name = "settled_at")
	private LocalDateTime settledAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected TimeDeposit() {
	}

	/**
	 * 가입. 만기일은 가입일 + 기간(개월)이다. 1월 31일 가입 1개월이면 2월 28일(윤년 29일)이다(LocalDate.plusMonths 가 말일로 맞춘다).
	 *
	 * @param account 방금 저장한 정기예금 계좌. 이 계좌의 id 가 계약의 id 가 된다(@MapsId)
	 */
	static TimeDeposit open(Account account, TimeDepositProduct product, long principal, Long payoutAccountId,
			LocalDate startDate) {
		if (principal <= 0) {
			throw new IllegalArgumentException("원금은 1원 이상이어야 합니다.");
		}
		TimeDeposit deposit = new TimeDeposit();
		deposit.account = account;
		deposit.product = product;
		deposit.principal = principal;
		deposit.annualRate = product.getAnnualRate();
		deposit.termMonths = product.getTermMonths();
		deposit.startDate = startDate;
		deposit.maturityDate = startDate.plusMonths(product.getTermMonths());
		deposit.payoutAccountId = payoutAccountId;
		deposit.status = TimeDepositStatus.ACTIVE;
		deposit.createdAt = DbTime.now();
		return deposit;
	}

	public boolean isActive() {
		return status == TimeDepositStatus.ACTIVE;
	}

	public boolean isDue(LocalDate today) {
		return isActive() && !today.isBefore(maturityDate);
	}

	/** 오늘 해지하면 받을 이자. 만기일 이후면 만기 해지 계산이다 */
	public TimeDepositInterest.Settlement quote(LocalDate today) {
		return TimeDepositInterest.settle(principal, annualRate, startDate, maturityDate, today);
	}

	/** 만기까지 두면 받을 이자 */
	public TimeDepositInterest.Settlement atMaturity() {
		return TimeDepositInterest.atMaturity(principal, annualRate, startDate, maturityDate);
	}

	/**
	 * 해지 기록. 이미 해지한 계약이면 거부한다. 상태 변경은 서비스가 계좌를 잠근 트랜잭션 안에서만 일어난다.
	 *
	 * @param interestPaymentId 이자 지급 기록. 이자 기간이 0일이면 null
	 */
	void settle(TimeDepositInterest.Settlement settlement, Long interestPaymentId, Long payoutTransferId) {
		if (!isActive()) {
			throw new AccountClosedException(accountId);
		}
		this.status = settlement.trigger() == InterestTrigger.MATURITY
				? TimeDepositStatus.MATURED
				: TimeDepositStatus.TERMINATED;
		this.appliedRate = settlement.appliedRate();
		this.interestPaymentId = interestPaymentId;
		this.payoutTransferId = payoutTransferId;
		this.settledAt = DbTime.now();
	}

}
