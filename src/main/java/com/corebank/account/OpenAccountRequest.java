package com.corebank.account;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 계좌 개설 요청 본문. 컨트롤러의 @Valid 가 아래 조건을 검사하고, 어기면 400 으로 응답한다.
 *
 * @param ownerName 예금주 이름. 관리자가 열 때 필수다(공백만 있으면 안 되고 100자까지).
 *                  고객이 열 때는 보내지 않아도 되고, 보내도 무시한다. 예금주는 언제나 가입한 고객 본인이다.
 * @param productId 예금 상품. 없으면 기본 상품(기본 입출금 통장). 판매 중지된 상품은 409
 */
public record OpenAccountRequest(
		@Size(max = 100) String ownerName,
		@Positive Long productId) {

	boolean hasOwnerName() {
		return ownerName != null && !ownerName.isBlank();
	}

}
