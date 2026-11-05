package com.corebank.account;

import com.corebank.auth.Actor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계좌 접근 권한. 관리자는 모든 계좌, 고객은 본인 계좌만 다룰 수 있다.
 *
 * <p>남의 계좌에 접근하면 403 이 아니라 404(ACCOUNT_NOT_FOUND)로 응답한다. 403 이면 "그 번호의 계좌가 있다" 는
 * 사실이 드러나, id 를 바꿔 가며 다른 고객의 계좌를 찾아낼 수 있기 때문이다.
 *
 * <p>확인과 거래 사이에 주인이 바뀌는 일은 없다(customer_id 는 한 번 정하면 바꾸지 않는다). 그래서 거래 트랜잭션
 * 밖에서 먼저 확인해도 안전하다.
 */
@Component
public class AccountAccessPolicy {

	private final AccountRepository accountRepository;

	public AccountAccessPolicy(AccountRepository accountRepository) {
		this.accountRepository = accountRepository;
	}

	/** @throws AccountNotFoundException 고객이 본인 계좌가 아닌(또는 없는) 계좌에 접근할 때 */
	@Transactional(readOnly = true)
	public void checkAccess(Actor actor, Long accountId) {
		if (actor.isAdmin()) {
			return; // 없는 계좌면 서비스가 404 를 낸다.
		}
		if (!accountRepository.existsByIdAndCustomerId(accountId, actor.customerId())) {
			throw new AccountNotFoundException(accountId);
		}
	}

}
