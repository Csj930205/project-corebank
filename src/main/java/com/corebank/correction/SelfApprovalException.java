package com.corebank.correction;

/** 요청한 관리자가 스스로 승인·반려하려 함. 403 */
public class SelfApprovalException extends RuntimeException {

	public SelfApprovalException(Long correctionId) {
		super("정정 요청은 요청한 사람이 아닌 다른 관리자가 결정해야 합니다. id=" + correctionId);
	}

}
