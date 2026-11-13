package com.corebank.product;

import com.corebank.common.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 예금 상품. 계좌는 상품 하나에 속하고(Account.product), 종류는 입출금(DemandDepositProduct)과 정기예금(TimeDepositProduct)이다.
 *
 * <p><b>상속 매핑(SINGLE_TABLE)</b>: 두 종류를 deposit_product 테이블 하나에 담고, product_type 열(구분 열)의 값으로
 * 어느 클래스로 만들지 정한다. 정기예금만 쓰는 열(term_months)은 입출금 상품에서 null 이다.
 * <ul>
 *   <li>JOINED(종류마다 테이블을 따로 두고 JOIN)를 쓰지 않은 이유: 종류별로 다른 열이 하나뿐이다. 계좌 목록이 상품을 함께
 *       읽을 때 JOIN 이 하나 더 붙고, 상품을 저장할 때 INSERT 가 두 번 나간다. 얻는 것(NOT NULL 을 열에 걸 수 있음)은
 *       CHECK 제약(ck_deposit_product_type)으로 대신한다.</li>
 *   <li>TABLE_PER_CLASS(종류마다 완전한 테이블)는 "모든 상품" 을 조회할 때 UNION 이 필요하고, 계좌가 상품 하나를 외래키로
 *       가리킬 수도 없어 쓰지 않는다.</li>
 * </ul>
 *
 * <p><b>지연 로딩 프록시와 instanceof</b>: Account.product 는 LAZY 라서 처음에는 DepositProduct 를 상속한 프록시가 들어 있다.
 * 프록시는 "DepositProduct 의 자식" 일 뿐 TimeDepositProduct 의 자식이 아니므로 {@code product instanceof TimeDepositProduct}
 * 가 실제로는 정기예금이어도 false 가 될 수 있다. 그래서 종류를 instanceof 로 묻지 않고 {@link #getType()} 같은 메서드로 묻는다.
 * 메서드 호출은 프록시가 실제 객체로 넘겨 주므로(다형성) 프록시여도 맞는 답이 나온다.
 *
 * <p><b>금리</b>: annual_rate 는 "지금" 금리다. 바꿀 때마다 deposit_product_rate 에 이력을 남기고(ProductService.changeRate),
 * 입출금 상품의 적수는 그날 적용된 금리를 이력에서 찾는다. 이미 쌓인 적수는 그날 금리를 함께 저장했으므로 바뀌지 않는다.
 */
@Getter
@Entity
@Table(name = "deposit_product")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "product_type", discriminatorType = DiscriminatorType.STRING, length = 10)
public abstract class DepositProduct {

	/** V15 가 넣는 기본 상품(입출금). 상품을 고르지 않고 연 계좌가 이 상품이다. */
	public static final long DEFAULT_ID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "deposit_product_seq_generator")
	@SequenceGenerator(name = "deposit_product_seq_generator", sequenceName = "deposit_product_seq", allocationSize = 1)
	private Long id;

	@Column(nullable = false, updatable = false, length = 50)
	private String name;

	// 지금 연이율(%). 금액·이율은 double 로 다루지 않는다(0.1 + 0.2 != 0.3). precision/scale 은 NUMBER(5,2) 와 맞춘다.
	@Column(name = "annual_rate", nullable = false, precision = 5, scale = 2)
	private BigDecimal annualRate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private ProductStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "discontinued_at")
	private LocalDateTime discontinuedAt;

	protected DepositProduct() {
	}

	protected DepositProduct(String name, BigDecimal annualRate) {
		this.name = name;
		this.annualRate = annualRate;
		this.status = ProductStatus.ACTIVE;
		this.createdAt = DbTime.now();
	}

	/** 새 입출금 상품. 이율 범위(0~20%, 소수 둘째 자리)는 요청 검증과 DB 제약이 확인한다. */
	public static DepositProduct of(String name, BigDecimal annualRate) {
		return new DemandDepositProduct(name, annualRate);
	}

	/** 상품 종류. 구분 열(product_type)과 같은 값이다. 프록시여도 맞는 답이 나온다(클래스 설명 참고). */
	public abstract ProductType getType();

	/** 가입 기간(개월). 정기예금만 있고, 입출금 상품은 null */
	public abstract Integer getTermMonths();

	public boolean isTimeDeposit() {
		return getType() == ProductType.TIME;
	}

	public boolean isActive() {
		return status == ProductStatus.ACTIVE;
	}

	/** 판매 중지. 이미 중지된 상품은 거부한다. 기본 상품은 서비스가 막는다(상품 없이 계좌를 열 수 없게 된다). */
	void discontinue() {
		if (status == ProductStatus.DISCONTINUED) {
			throw new ProductNotAvailableException(id);
		}
		status = ProductStatus.DISCONTINUED;
		discontinuedAt = DbTime.now();
	}

	/** 지금 금리를 바꾼다. 이력(DepositProductRate)은 서비스가 함께 남긴다. 판매 중지된 상품도 바꿀 수 있다(이미 연 입출금 계좌). */
	void changeRate(BigDecimal annualRate) {
		this.annualRate = annualRate;
	}

}
