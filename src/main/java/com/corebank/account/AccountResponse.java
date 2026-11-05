package com.corebank.account;

import java.time.LocalDateTime;

/**
 * 계좌 API 응답. 엔티티를 그대로 내보내지 않아 DB 구조가 바뀌어도 API 형식을 유지할 수 있다.
 */
public record AccountResponse(
		Long id,
		String accountNumber,
		String ownerName,
		// 계좌 주인(고객 번호). 고객 기능 전에 만든 계좌는 null
		Long customerId,
		long balance,
		AccountStatus status,
		LocalDateTime createdAt,
		// 정상 계좌는 null
		LocalDateTime closedAt) {

	public static AccountResponse from(Account account) {
		return new AccountResponse(
				account.getId(),
				account.getAccountNumber(),
				account.getOwnerName(),
				account.getCustomerId(),
				account.getBalance(),
				account.getStatus(),
				account.getCreatedAt(),
				account.getClosedAt());
	}

}
