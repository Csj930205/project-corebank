package com.corebank.account;

/** 정기예금 계좌에 입출금 거래(입금·출금·이체·해지·정정)를 하려 함. 정기예금은 중도해지로만 돈을 뺄 수 있다. 409 */
public class TimeDepositRestrictedException extends RuntimeException {

	public TimeDepositRestrictedException(Long accountId) {
		super("정기예금 계좌는 입출금·이체를 할 수 없습니다. 중도해지를 이용하세요. id=" + accountId);
	}

}
