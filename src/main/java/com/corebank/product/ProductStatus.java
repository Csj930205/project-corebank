package com.corebank.product;

/** 상품 판매 상태. DB 의 ck_deposit_product_status 와 값이 일치해야 한다. */
public enum ProductStatus {
	/** 새 계좌를 열 수 있다 */
	ACTIVE,
	/** 판매 중지. 새 계좌는 못 열지만, 이미 연 계좌는 이 상품의 금리로 계속 이자가 붙는다 */
	DISCONTINUED
}
