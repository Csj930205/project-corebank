package com.corebank.correction;

/** 없는 정정 요청. 404 */
public class CorrectionNotFoundException extends RuntimeException {

	public CorrectionNotFoundException(Long id) {
		super("정정 요청을 찾을 수 없습니다. id=" + id);
	}

}
