package com.corebank.account;

import com.corebank.product.DepositProduct;
import com.corebank.product.ProductType;

import java.math.BigDecimal;
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
		LocalDateTime closedAt,
		Product product) {

	/**
	 * 계좌의 예금 상품. annualRate 는 상품의 지금 연이율(%)이다. 정기예금 계좌의 약정이율은 GET /accounts/{id}/time-deposit 에 있다
	 * (가입 뒤 상품 금리가 바뀌어도 약정이율은 그대로다).
	 */
	public record Product(Long id, String name, ProductType type, BigDecimal annualRate) {
	}

	public static AccountResponse from(Account account) {
		return new AccountResponse(
				account.getId(),
				account.getAccountNumber(),
				account.getOwnerName(),
				account.getCustomerId(),
				account.getBalance(),
				account.getStatus(),
				account.getCreatedAt(),
				account.getClosedAt(),
				// 여기서 상품 이름을 읽는 순간 LAZY 프록시가 초기화된다. 목록 쿼리가 상품을 함께 읽지 않았다면 계좌마다 쿼리가 나간다.
				product(account.getProduct()));
	}

	private static Product product(DepositProduct product) {
		return product == null ? null : new Product(product.getId(), product.getName(), product.getType(),
				product.getAnnualRate());
	}

}
