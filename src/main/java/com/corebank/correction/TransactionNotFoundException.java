package com.corebank.correction;

/** 정정하려는 원장 거래가 없다. 404 */
public class TransactionNotFoundException extends RuntimeException {

	public TransactionNotFoundException(Long id) {
		super("원장 거래를 찾을 수 없습니다. id=" + id);
	}

}
