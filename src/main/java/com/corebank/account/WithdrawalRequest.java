package com.corebank.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 출금 요청 본문.
 *
 * @param amount 출금액 (원). 입금 요청과 같은 규칙으로 1원 이상의 정수만 받는다.
 */
public record WithdrawalRequest(
		@NotNull @Positive Long amount) {
}
