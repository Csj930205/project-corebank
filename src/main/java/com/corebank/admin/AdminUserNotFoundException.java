package com.corebank.admin;

/** 없는 관리자 등록 요청. 404 ADMIN_NOT_FOUND */
public class AdminUserNotFoundException extends RuntimeException {

	public AdminUserNotFoundException(Long id) {
		super("관리자를 찾을 수 없습니다. id=" + id);
	}

}
