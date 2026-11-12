package com.corebank.admin;

/**
 * 등록을 요청한 관리자가 스스로 승인·반려하려 함. 403 SELF_APPROVAL_NOT_ALLOWED(거래 정정과 같은 코드).
 *
 * <p>요청은 운영자, 결정은 책임자만 하므로 역할 규칙(SecurityConfig)에서 먼저 막힌다. 그래도 엔티티와 DB CHECK 에
 * 한 번 더 둔다. URL 규칙은 고치다 실수하기 쉽고, 이 규칙이 깨지면 4-eyes 전체가 깨지기 때문이다.
 */
public class AdminSelfApprovalException extends RuntimeException {

	public AdminSelfApprovalException(Long id) {
		super("관리자 등록은 요청한 사람이 아닌 다른 관리자가 결정해야 합니다. id=" + id);
	}

}
