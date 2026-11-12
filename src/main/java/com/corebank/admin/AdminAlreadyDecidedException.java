package com.corebank.admin;

/** 이미 승인·반려된 관리자 등록을 다시 결정하려 함(두 책임자가 동시에 결정한 경우 포함). 409 ADMIN_ALREADY_DECIDED */
public class AdminAlreadyDecidedException extends RuntimeException {

	public AdminAlreadyDecidedException(Long id) {
		super("이미 승인 또는 반려된 관리자 등록입니다. id=" + id);
	}

}
