package com.corebank.closing;

import java.math.BigDecimal;

/**
 * 불일치 한 건.
 *
 * @param expected 원장 기준으로 맞아야 하는 값
 * @param actual   실제로 비교한 값
 */
public record ClosingMismatch(
		Long id,
		MismatchType type,
		Long accountId,
		Long transactionId,
		Long transferId,
		BigDecimal expected,
		BigDecimal actual,
		String detail) {

	static ClosingMismatch ofAccount(MismatchType type, Long accountId, BigDecimal expected, BigDecimal actual,
			String detail) {
		return new ClosingMismatch(null, type, accountId, null, null, expected, actual, detail);
	}

}
