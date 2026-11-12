package com.corebank.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** @param annualRate 연이율(%). JSON 숫자로 나간다(2.35). 소수 둘째 자리까지라 JS number 로도 정확하다 */
public record ProductResponse(Long id, String name, BigDecimal annualRate, ProductStatus status,
		LocalDateTime createdAt, LocalDateTime discontinuedAt) {

	public static ProductResponse from(DepositProduct product) {
		return new ProductResponse(product.getId(), product.getName(), product.getAnnualRate(), product.getStatus(),
				product.getCreatedAt(), product.getDiscontinuedAt());
	}

}
