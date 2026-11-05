package com.corebank.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 요청한 사람. 토큰(JWT)의 scope 로 관리자와 고객을 구분한다.
 *
 * <ul>
 *   <li>관리자: 모든 계좌를 다룰 수 있다(창구 직원 역할).</li>
 *   <li>고객: 본인 계좌만 다룰 수 있다. customerId 는 토큰의 cid claim 이다.</li>
 * </ul>
 */
public record Actor(Role role, String username, Long customerId) {

	public enum Role {
		ADMIN, CUSTOMER
	}

	public static final String SCOPE_ADMIN = "ADMIN";
	public static final String SCOPE_CUSTOMER = "CUSTOMER";
	public static final String CUSTOMER_ID_CLAIM = "cid";

	public static Actor from(Jwt jwt) {
		String scope = jwt.getClaimAsString("scope");
		if (SCOPE_ADMIN.equals(scope)) {
			return new Actor(Role.ADMIN, jwt.getSubject(), null);
		}
		if (SCOPE_CUSTOMER.equals(scope)) {
			Object cid = jwt.getClaim(CUSTOMER_ID_CLAIM);
			if (cid instanceof Number number) {
				return new Actor(Role.CUSTOMER, jwt.getSubject(), number.longValue());
			}
		}
		// 서명은 맞지만 우리가 발급하지 않는 형태의 토큰. 보안 필터가 403 FORBIDDEN 으로 바꾼다.
		throw new AccessDeniedException("알 수 없는 권한의 토큰입니다.");
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	public boolean isCustomer() {
		return role == Role.CUSTOMER;
	}

}
