package com.corebank.account;

import com.corebank.common.DbTime;
import com.corebank.product.DepositProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * account 테이블과 연결되는 JPA 엔티티.
 *
 * <p>setter 를 두지 않아 외부에서 잔액이나 상태를 마음대로 바꿀 수 없다.
 * 이후 입금, 출금 같은 변경은 의미가 드러나는 메서드로 추가한다.
 */
@Getter
@Entity
@Table(name = "account")
public class Account {

	// Oracle 시퀀스 account_seq 로 id 를 만든다. allocationSize 는 시퀀스의 INCREMENT BY 와 같아야 한다.
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "account_seq_generator")
	@SequenceGenerator(name = "account_seq_generator", sequenceName = "account_seq", allocationSize = 1)
	private Long id;

	// updatable = false: 한 번 저장한 계좌번호는 UPDATE 문에 포함되지 않는다.
	@Column(name = "account_number", nullable = false, updatable = false, length = 13)
	private String accountNumber;

	// 앱 DB 계정은 잔액·상태·해지 시각 열만 UPDATE 할 수 있다(R__app_user_grants.sql). 나머지 열은 UPDATE 문에서 뺀다.
	@Column(name = "owner_name", nullable = false, updatable = false, length = 100)
	private String ownerName;

	// 원 단위 정수. double 은 소수 계산 오차가 생기므로 금액에 쓰지 않는다.
	@Column(nullable = false)
	private long balance;

	// STRING: 숫자 순번이 아닌 "ACTIVE" 같은 이름으로 저장해, enum 순서가 바뀌어도 데이터가 깨지지 않는다.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private AccountStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	// 계좌 주인(customer.id). 고객 기능 전에 만든 계좌와 관리자가 이름만으로 연 계좌는 null 이고, 관리자만 다룬다.
	// 연관관계(@ManyToOne)로 두지 않고 값만 가진다. 소유자 확인에는 번호만 있으면 되고, 고객 엔티티와 결합하지 않기 위해서다.
	@Column(name = "customer_id", updatable = false)
	private Long customerId;

	/*
	 * 예금 상품(금리). customerId 처럼 값만 두지 않고 연관관계로 둔 이유: 계좌 화면과 목록이 늘 상품 이름·금리를 함께 보여 주고,
	 * 이자 적립이 계좌의 금리를 쓴다. "계좌 → 상품" 한 방향만 둔다(상품에서 계좌 목록으로 가는 @OneToMany 는 만들지 않는다).
	 * 상품 하나에 계좌가 수십만 개 붙을 수 있어, 그 컬렉션을 읽는 순간 전부 메모리로 올라오기 때문이다.
	 *
	 * LAZY: @ManyToOne 기본값(EAGER)이면 계좌를 읽을 때마다 상품을 바로 조회한다. 목록 20건이면 상품 조회가 따로 나간다(N+1).
	 * LAZY 면 처음에는 프록시(빈 껍데기)를 넣어 두고, getName() 처럼 실제 값을 읽을 때 조회한다. 목록 쿼리는
	 * @EntityGraph 로 계좌와 상품을 한 번에 읽는다(AccountRepository).
	 * updatable = false: 계좌의 상품은 바꾸지 않는다(앱 DB 계정도 이 열의 UPDATE 권한이 없다).
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	private DepositProduct product;

	// 해지 시각. 정상 계좌는 null 이다. DB 의 ck_account_closed 제약이 상태와 짝이 맞는지 확인한다.
	@Column(name = "closed_at")
	private LocalDateTime closedAt;

	/**
	 * 같은 계좌인지는 id 로만 본다. 잔액·상태처럼 바뀌는 값으로 비교하면, 입금 한 번에 같은 계좌가 "다른 계좌" 가 되고
	 * Set·Map 에 넣어 둔 계좌를 찾지 못한다.
	 *
	 * <ul>
	 *   <li>저장 전(id 가 null)인 계좌는 자기 자신하고만 같다. 아직 DB 의 어느 행인지 정해지지 않았기 때문이다.</li>
	 *   <li>getClass() 대신 instanceof 를, 필드 대신 getId() 를 쓴다(자기 쪽도). 지연 로딩 프록시는 Account 를 상속한
	 *       다른 클래스이고 필드가 비어 있어, getter 로 읽어야 실제 값이 나온다. 같은 이유로 final 로 두지 않는다
	 *       (final 이면 프록시가 가로채지 못해 프록시의 빈 필드로 비교하게 된다).</li>
	 * </ul>
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Account other)) {
			return false;
		}
		return getId() != null && getId().equals(other.getId());
	}

	/**
	 * 계좌마다 다르지 않고 클래스마다 같은 값이다. id 로 만들면 저장 전(null)과 저장 후(시퀀스 값)의 해시가 달라져,
	 * 저장 전에 Set 에 넣은 계좌를 저장 뒤에 찾지 못한다. 같은 버킷에 모여 느려지는 것은 계좌를 수백만 개씩
	 * 한 컬렉션에 담지 않으므로 문제가 되지 않는다.
	 */
	@Override
	public int hashCode() {
		return Account.class.hashCode();
	}

	/** JPA 가 DB 조회 결과로 객체를 만들 때 쓰는 기본 생성자. 애플리케이션 코드에서는 쓰지 않는다. */
	protected Account() {
	}

	private Account(String accountNumber, String ownerName, Long customerId, DepositProduct product,
			LocalDateTime createdAt) {
		this.accountNumber = accountNumber;
		this.product = product;
		this.ownerName = ownerName;
		this.customerId = customerId;
		this.balance = 0;
		this.status = AccountStatus.ACTIVE;
		this.createdAt = createdAt;
	}

	/** 새 계좌를 만든다. 이 메서드로만 생성되므로 모든 계좌는 잔액 0, ACTIVE 상태로 시작한다. */
	public static Account open(String accountNumber, String ownerName, DepositProduct product) {
		return new Account(accountNumber, ownerName, null, product, DbTime.now());
	}

	/** 고객 명의로 새 계좌를 만든다. 예금주 이름은 고객 이름이다. */
	public static Account openFor(String accountNumber, String ownerName, Long customerId, DepositProduct product) {
		return new Account(accountNumber, ownerName, customerId, product, DbTime.now());
	}

	/** 이 고객의 계좌인지 */
	public boolean isOwnedBy(Long customerId) {
		return this.customerId != null && this.customerId.equals(customerId);
	}

	/** 입금한다. 해지 계좌는 거부하고, 1원 미만은 호출 코드의 버그이므로 막는다. */
	public void deposit(long amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("입금액은 1원 이상이어야 합니다.");
		}
		if (status == AccountStatus.CLOSED) {
			throw new AccountClosedException(id);
		}
		try {
			// 일반 덧셈은 범위를 넘으면 조용히 음수가 되므로, 예외가 나는 addExact 를 쓴다.
			balance = Math.addExact(balance, amount);
		} catch (ArithmeticException e) {
			throw new BalanceLimitExceededException(id);
		}
	}

	/**
	 * 출금한다. 해지 계좌와 잔액보다 큰 금액은 거부한다.
	 *
	 * <p>잔액 확인과 차감이 한 메서드 안에 있지만, 동시 출금으로부터 지켜 주는 것은 이 메서드가 아니라
	 * 서비스의 비관적 락이다. 락 없이 두 요청이 같은 잔액을 동시에 읽으면 둘 다 확인을 통과한다.
	 */
	public void withdraw(long amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("출금액은 1원 이상이어야 합니다.");
		}
		if (status == AccountStatus.CLOSED) {
			throw new AccountClosedException(id);
		}
		if (amount > balance) {
			throw new InsufficientBalanceException(id);
		}
		balance -= amount;
	}

	/**
	 * 해지한다. 남은 잔액은 고객에게 지급할 금액으로 돌려주고 잔액은 0이 된다. 이미 해지된 계좌는 거부한다.
	 *
	 * <p>지급 금액을 원장에 기록하는 일은 서비스가 한다. 엔티티는 원장을 모른다.
	 *
	 * @return 고객에게 지급할 금액. 잔액이 없었으면 0
	 */
	public long close() {
		if (status == AccountStatus.CLOSED) {
			throw new AccountClosedException(id);
		}
		long payout = balance;
		balance = 0;
		status = AccountStatus.CLOSED;
		closedAt = DbTime.now();
		return payout;
	}

}
