package com.corebank.transfer;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 이체 요청 본문. 받는 계좌는 id 또는 계좌번호 중 하나로 정한다(둘 다 주거나 둘 다 빼면 400).
 * 고객 화면은 남의 계좌 id 를 알 수 없으므로 계좌번호를 쓴다. 같은 계좌 여부는 업무 규칙이라 서비스에서 검사한다.
 *
 * @param fromAccountId   보내는 계좌 id
 * @param toAccountId     받는 계좌 id
 * @param toAccountNumber 받는 계좌번호 (숫자 13자리)
 * @param amount          이체액 (원). 1원 이상의 정수
 */
public record TransferRequest(
		@NotNull Long fromAccountId,
		Long toAccountId,
		@Pattern(regexp = "\\d{13}", message = "계좌번호는 숫자 13자리입니다.") String toAccountNumber,
		@NotNull @Positive Long amount) {

	@AssertTrue(message = "받는 계좌는 toAccountId 와 toAccountNumber 중 하나만 보내야 합니다.")
	public boolean isOneRecipient() {
		return (toAccountId == null) != (toAccountNumber == null);
	}

}
