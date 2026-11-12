package com.corebank.product;

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
import java.time.LocalDateTime;

/**
 * 예금 상품. 계좌는 상품 하나에 속하고(Account.product), 이자는 상품의 연이율로 계산한다.
 *
 * <p>금리는 바꾸지 않는다(updatable = false). 금리를 바꾸려면 새 상품을 만들고 이전 상품은 판매 중지한다.
 * 이미 연 계좌의 금리가 은행 마음대로 바뀌지 않게 하는 단순한 방법이다. 적수에는 그날의 금리를 함께 남기므로
 * (interest_accrual.annual_rate), 나중에 금리 변경 기능을 넣어도 지난 이자는 바뀌지 않는다.
 */
@Getter
@Entity
@Table(name = "deposit_product")
public class DepositProduct {

	/** V15 가 넣는 기본 상품. 상품을 고르지 않고 연 계좌가 이 상품이다. */
	public static final long DEFAULT_ID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "deposit_product_seq_generator")
	@SequenceGenerator(name = "deposit_product_seq_generator", sequenceName = "deposit_product_seq", allocationSize = 1)
	private Long id;

	@Column(nullable = false, updatable = false, length = 50)
	private String name;

	// 연이율(%). 금액·이율은 double 로 다루지 않는다(0.1 + 0.2 != 0.3). precision/scale 은 NUMBER(5,2) 와 맞춘다.
	@Column(name = "annual_rate", nullable = false, updatable = false, precision = 5, scale = 2)
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

	private DepositProduct(String name, BigDecimal annualRate) {
		this.name = name;
		this.annualRate = annualRate;
		this.status = ProductStatus.ACTIVE;
		this.createdAt = DbTime.now();
	}

	/** 새 상품. 이율 범위(0~20%, 소수 둘째 자리)는 요청 검증과 DB 제약이 확인한다. */
	public static DepositProduct of(String name, BigDecimal annualRate) {
		return new DepositProduct(name, annualRate);
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

}
