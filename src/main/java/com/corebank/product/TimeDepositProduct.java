package com.corebank.product;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * 정기예금 상품. 구분 열 product_type = 'TIME'. 가입 기간(개월)이 정해져 있다.
 *
 * <p>term_months 는 테이블에서 nullable 이다(입출금 상품 행은 null). 대신 ck_deposit_product_type 이
 * "정기예금이면 1~60" 을 확인한다. SINGLE_TABLE 의 자식 클래스 열에는 NOT NULL 을 걸 수 없는 대가다.
 */
@Entity
@DiscriminatorValue("TIME")
public class TimeDepositProduct extends DepositProduct {

	public static final int MAX_TERM_MONTHS = 60;

	@Column(name = "term_months", updatable = false)
	private Integer termMonths;

	protected TimeDepositProduct() {
	}

	private TimeDepositProduct(String name, BigDecimal annualRate, int termMonths) {
		super(name, annualRate);
		this.termMonths = termMonths;
	}

	public static TimeDepositProduct of(String name, BigDecimal annualRate, int termMonths) {
		if (termMonths < 1 || termMonths > MAX_TERM_MONTHS) {
			throw new IllegalArgumentException("가입 기간은 1~" + MAX_TERM_MONTHS + "개월입니다: " + termMonths);
		}
		return new TimeDepositProduct(name, annualRate, termMonths);
	}

	@Override
	public ProductType getType() {
		return ProductType.TIME;
	}

	@Override
	public Integer getTermMonths() {
		return termMonths;
	}

}
