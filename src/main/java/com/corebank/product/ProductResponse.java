package com.corebank.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param annualRate 지금 연이율(%). JSON 숫자로 나간다(2.35). 소수 둘째 자리까지라 JS number 로도 정확하다
 * @param termMonths 정기예금 가입 기간(개월). 입출금 상품은 null
 */
public record ProductResponse(Long id, String name, ProductType type, BigDecimal annualRate, Integer termMonths,
		ProductStatus status, LocalDateTime createdAt, LocalDateTime discontinuedAt) {

	public static ProductResponse from(DepositProduct product) {
		return new ProductResponse(product.getId(), product.getName(), product.getType(), product.getAnnualRate(),
				product.getTermMonths(), product.getStatus(), product.getCreatedAt(), product.getDiscontinuedAt());
	}

}
