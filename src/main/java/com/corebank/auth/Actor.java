package com.corebank.auth;

import com.corebank.admin.AdminRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 요청한 사람. 토큰(JWT)의 scope 로 관리자와 고객을 구분한다.
 *
 * <ul>
 *   <li>관리자: 모든 계좌를 볼 수 있다. 무엇을 바꿀 수 있는지는 역할(adminRole, 토큰의 role claim)이 정한다.</li>
 *   <li>고객: 본인 계좌만 다룰 수 있다. customerId 는 토큰의 cid claim 이다.</li>
 * </ul>
 *
 * @param adminRole 관리자 역할. 고객은 null
 */
public record Actor(Role role, String username, Long customerId, AdminRole adminRole) {

	public enum Role {
		ADMIN, CUSTOMER
	}

	public static final String SCOPE_ADMIN = "ADMIN";
	public static final String SCOPE_CUSTOMER = "CUSTOMER";
	public static final String CUSTOMER_ID_CLAIM = "cid";
	public static final String ROLE_CLAIM = "role";

	public static Actor from(Jwt jwt) {
		String scope = jwt.getClaimAsString("scope");
		if (SCOPE_ADMIN.equals(scope)) {
			return new Actor(Role.ADMIN, jwt.getSubject(), null, adminRole(jwt));
		}
		if (SCOPE_CUSTOMER.equals(scope)) {
			Object cid = jwt.getClaim(CUSTOMER_ID_CLAIM);
			if (cid instanceof Number number) {
				return new Actor(Role.CUSTOMER, jwt.getSubject(), number.longValue(), null);
			}
		}
		// 서명은 맞지만 우리가 발급하지 않는 형태의 토큰. 보안 필터가 403 FORBIDDEN 으로 바꾼다.
		throw new AccessDeniedException("알 수 없는 권한의 토큰입니다.");
	}

	/** 역할 기능 전에 발급된 관리자 토큰에는 role 이 없다. 30분 안에 만료되고, 그동안은 조회만 된다. */
	private static AdminRole adminRole(Jwt jwt) {
		String role = jwt.getClaimAsString(ROLE_CLAIM);
		if (role == null) {
			return null;
		}
		try {
			return AdminRole.valueOf(role);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	public boolean isCustomer() {
		return role == Role.CUSTOMER;
	}

}
