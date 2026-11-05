package com.corebank.closing;

/** 없는 마감 실행 조회. 404 */
public class ClosingNotFoundException extends RuntimeException {

	public ClosingNotFoundException(Long id) {
		super("마감 실행을 찾을 수 없습니다. id=" + id);
	}

}
