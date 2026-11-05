package com.corebank.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 입금 요청 본문.
 *
 * @param amount 입금액 (원). 값이 없으면 안 되므로 기본값이 0인 long 이 아니라 Long 을 쓴다.
 */
public record DepositRequest(
		@NotNull @Positive Long amount) {
}
