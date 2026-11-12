package com.corebank.correction;

import lombok.Getter;

/**
 * 정정 요청이 현재 상태와 충돌한다. 409. 원인마다 에러 코드가 다르다.
 */
@Getter
public class CorrectionConflictException extends RuntimeException {

	private final String code;

	private CorrectionConflictException(String code, String message) {
		super(message);
		this.code = code;
	}

	/** 이미 역분개된 거래 */
	static CorrectionConflictException alreadyReversed(Long transactionId) {
		return new CorrectionConflictException("ALREADY_REVERSED", "이미 정정된 거래입니다. id=" + transactionId);
	}

	/** 같은 거래에 승인을 기다리는 요청이 이미 있다 */
	static CorrectionConflictException alreadyRequested(Long transactionId) {
		return new CorrectionConflictException("CORRECTION_ALREADY_REQUESTED",
				"이 거래에 승인을 기다리는 정정 요청이 이미 있습니다. id=" + transactionId);
	}

	/** 이미 승인·반려된 요청을 다시 결정하려 함(동시에 결정한 경우 포함) */
	static CorrectionConflictException alreadyDecided(Long correctionId) {
		return new CorrectionConflictException("CORRECTION_ALREADY_DECIDED",
				"이미 승인 또는 반려된 정정 요청입니다. id=" + correctionId);
	}

}
