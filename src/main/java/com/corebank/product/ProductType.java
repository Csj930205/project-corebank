package com.corebank.product;

/** 상품 종류. deposit_product.product_type(구분 열)의 값이고, ck_deposit_product_type 과 값이 일치해야 한다. */
public enum ProductType {
	/** 입출금 통장. 언제든 넣고 빼며, 매일 마감 잔액에 그날 금리로 이자(적수)가 쌓인다 */
	DEMAND,
	/** 정기예금. 원금을 한 번 넣고 기간이 끝날 때까지 두며, 가입할 때 금리가 고정된다 */
	TIME
}
