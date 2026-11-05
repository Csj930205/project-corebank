package com.corebank.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 요청 → 감사 종류 판정. DB 없이 빠르게 돈다. */
class AuditActionTest {

	/** 고정 경로(/accounts/lookup)가 변수 경로(/accounts/{id})보다 먼저 맞는다. */
	@Test
	void lookupIsNotMistakenForAccountId() {
		assertThat(AuditAction.match("GET", "/accounts/lookup"))
				.hasValue(new AuditAction.Match(AuditAction.RECIPIENT_LOOKUP, null));
		assertThat(AuditAction.match("GET", "/accounts/42"))
				.hasValue(new AuditAction.Match(AuditAction.ACCOUNT_VIEW, 42L));
	}

	/** 같은 경로라도 메서드로 나뉜다. */
	@Test
	void methodDecidesAction() {
		assertThat(AuditAction.match("POST", "/accounts")).get()
				.extracting(AuditAction.Match::action).isEqualTo(AuditAction.ACCOUNT_OPEN);
		assertThat(AuditAction.match("GET", "/accounts")).get()
				.extracting(AuditAction.Match::action).isEqualTo(AuditAction.MY_ACCOUNTS);
	}

	/** 마감 상세의 {id} 는 계좌가 아니다. 숫자가 아닌 계좌 id 는 대상 계좌 없이 남는다. */
	@Test
	void onlyAccountPathsHaveTargetAccount() {
		assertThat(AuditAction.match("GET", "/admin/closings/7"))
				.hasValue(new AuditAction.Match(AuditAction.CLOSING_VIEW, null));
		assertThat(AuditAction.match("POST", "/accounts/abc/deposits"))
				.hasValue(new AuditAction.Match(AuditAction.DEPOSIT, null));
	}

	/** 남기지 않는 요청 */
	@Test
	void unknownRequestsAreNotAudited() {
		assertThat(AuditAction.match("GET", "/auth/me")).isEmpty();
		assertThat(AuditAction.match("DELETE", "/accounts/1")).isEmpty();
		assertThat(AuditAction.match("GET", "/no-such-path")).isEmpty();
	}

}
