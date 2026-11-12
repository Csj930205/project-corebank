package com.corebank.product;

/** 같은 이름의 상품이 이미 있다. 409 */
public class ProductNameTakenException extends RuntimeException {

	public ProductNameTakenException(String name) {
		super("같은 이름의 상품이 이미 있습니다: " + name);
	}

}
