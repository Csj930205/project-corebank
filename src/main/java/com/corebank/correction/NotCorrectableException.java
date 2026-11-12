package com.corebank.correction;

import com.corebank.ledger.TransactionType;

/** 정정할 수 없는 거래 종류(해지 지급, 역분개). 422 */
public class NotCorrectableException extends RuntimeException {

	public NotCorrectableException(Long transactionId, TransactionType type) {
		super("정정할 수 없는 거래입니다(" + type + "). id=" + transactionId);
	}

}
