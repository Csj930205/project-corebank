package com.corebank.account;

/**
 * 수취인 조회 응답. 이체 전에 "이 계좌번호가 맞는 사람인지" 확인하는 용도라, 가린 예금주 이름만 준다.
 * 내부 id, 잔액, 개설일 같은 다른 정보는 주지 않는다.
 *
 * @param accountNumber 조회한 계좌번호
 * @param ownerName     가린 예금주 이름 (예: 홍*동)
 */
public record RecipientResponse(String accountNumber, String ownerName) {

	static RecipientResponse from(Account account) {
		return new RecipientResponse(account.getAccountNumber(), NameMasking.mask(account.getOwnerName()));
	}

}
