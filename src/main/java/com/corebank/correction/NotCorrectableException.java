package com.corebank.correction;

import com.corebank.ledger.TransactionType;

/** 정정할 수 없는 거래(해지 지급, 역분개, 이자, 정기예금 계좌의 거래). 422 */
public class NotCorrectableException extends RuntimeException {

	public NotCorrectableException(Long transactionId, TransactionType type) {
		super("정정할 수 없는 거래입니다(" + type + "). id=" + transactionId);
	}

	private NotCorrectableException(String message) {
		super(message);
	}

	/** 정기예금 가입 원금 이체, 해지 원리금 이체. 되돌리면 계약(원금·상태)과 잔액이 어긋난다 */
	public static NotCorrectableException timeDeposit(Long transactionId) {
		return new NotCorrectableException("정기예금 계좌의 거래는 정정할 수 없습니다. id=" + transactionId);
	}

}
