package com.corebank.product;

/** 판매 중지된 상품으로 계좌를 열거나, 이미 중지된(또는 기본) 상품을 중지하려 함. 409 */
public class ProductNotAvailableException extends RuntimeException {

	public ProductNotAvailableException(Long id) {
		super("판매 중인 상품이 아닙니다. id=" + id);
	}

	ProductNotAvailableException(String message) {
		super(message);
	}

}
