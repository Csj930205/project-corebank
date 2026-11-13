package com.corebank.product;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/** 입출금 상품. 구분 열 product_type = 'DEMAND'. 정기예금과 달리 따로 가진 값이 없다. */
@Entity
@DiscriminatorValue("DEMAND")
public class DemandDepositProduct extends DepositProduct {

	protected DemandDepositProduct() {
	}

	DemandDepositProduct(String name, BigDecimal annualRate) {
		super(name, annualRate);
	}

	@Override
	public ProductType getType() {
		return ProductType.DEMAND;
	}

	@Override
	public Integer getTermMonths() {
		return null;
	}

}
