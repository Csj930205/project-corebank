package com.corebank.admin;

/**
 * 관리자 계정 상태. 등록을 요청하면 PENDING 이고, 책임자가 승인해야 ACTIVE 가 되어 로그인할 수 있다.
 * 서버 설정(.env)으로 만든 계정은 처음부터 ACTIVE 다.
 */
public enum AdminUserStatus {
	PENDING, ACTIVE, REJECTED
}
