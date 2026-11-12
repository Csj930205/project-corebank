package com.corebank.correction;

/** 정정 요청 상태. DB 의 ck_correction_request_status 제약과 값이 일치해야 한다. */
public enum CorrectionStatus {
	/** 승인을 기다린다 */
	REQUESTED,
	/** 승인됐고 역분개가 기록됐다 */
	APPROVED,
	/** 반려됐다. 원장은 그대로다 */
	REJECTED
}
