package com.corebank.product;

/** 계좌 종류와 맞지 않는 상품. 입출금 계좌를 정기예금 상품으로 열거나, 정기예금을 입출금 상품으로 가입하려 함. 422 */
public class ProductTypeMismatchException extends RuntimeException {

	public ProductTypeMismatchException(Long productId, ProductType expected) {
		super(switch (expected) {
			case DEMAND -> "입출금 계좌를 열 수 있는 상품이 아닙니다. id=" + productId;
			case TIME -> "정기예금 상품이 아닙니다. id=" + productId;
		});
	}

}
