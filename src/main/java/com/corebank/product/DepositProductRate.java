package com.corebank.product;

import com.corebank.common.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 상품 금리 이력 한 줄. effective_from 날짜부터 이 금리다. 추가만 한다(모든 열 updatable = false, 앱 계정은 INSERT 만).
 *
 * <p>상품(DepositProduct)과 연관관계를 맺지 않고 번호만 가진다. 이력은 상품 화면에서 따로 조회하고(최신순),
 * 상품 쪽에 @OneToMany 컬렉션을 두면 상품을 읽을 때마다 이력을 언제 읽을지(LAZY·N+1)를 신경 써야 한다.
 */
@Getter
@Entity
@Table(name = "deposit_product_rate")
public class DepositProductRate {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "deposit_product_rate_seq_generator")
	@SequenceGenerator(name = "deposit_product_rate_seq_generator", sequenceName = "deposit_product_rate_seq",
			allocationSize = 1)
	private Long id;

	@Column(name = "product_id", nullable = false, updatable = false)
	private Long productId;

	@Column(name = "annual_rate", nullable = false, updatable = false, precision = 5, scale = 2)
	private BigDecimal annualRate;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private LocalDate effectiveFrom;

	// 바꾼 관리자. 상품을 만들 때의 첫 금리는 null
	@Column(name = "changed_by", updatable = false, length = 50)
	private String changedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected DepositProductRate() {
	}

	static DepositProductRate of(Long productId, BigDecimal annualRate, LocalDate effectiveFrom, String changedBy) {
		DepositProductRate rate = new DepositProductRate();
		rate.productId = productId;
		rate.annualRate = annualRate;
		rate.effectiveFrom = effectiveFrom;
		rate.changedBy = changedBy;
		rate.createdAt = DbTime.now();
		return rate;
	}

}
