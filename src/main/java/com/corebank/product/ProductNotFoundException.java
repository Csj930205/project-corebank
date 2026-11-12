package com.corebank.product;

/** 없는 상품. 404 */
public class ProductNotFoundException extends RuntimeException {

	public ProductNotFoundException(Long id) {
		super("상품을 찾을 수 없습니다. id=" + id);
	}

}
