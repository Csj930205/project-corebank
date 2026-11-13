package com.corebank.auth;

import com.corebank.admin.AdminRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 액세스 토큰(JWT) 발급.
 *
 * <p>토큰 안의 정보(claim):
 * <ul>
 *   <li>iss: 발급자. 검증할 때 우리 서버가 발급한 토큰인지 확인한다.</li>
 *   <li>sub: 로그인 아이디(관리자 또는 고객)</li>
 *   <li>scope: 누구인가(ADMIN 또는 CUSTOMER). Spring Security 가 "SCOPE_ADMIN" 같은 권한으로 바꿔 접근을 판단한다.</li>
 *   <li>role: 관리자 토큰에만. 무슨 일을 맡았나(TELLER, APPROVER, OPERATOR). "ROLE_TELLER" 같은 권한이 된다(SecurityConfig).</li>
 *   <li>cid, name: 고객 토큰에만. 고객 번호와 이름. 계좌 소유자 확인은 cid 로 한다.</li>
 *   <li>sid: 로그인 세션 번호(auth_session.id). 요청마다 세션이 살아 있는지 확인한다(JwtConfig 의 검증기).
 *       로그아웃하거나 세션이 끊기면 만료 전이라도 그 즉시 401 이다.</li>
 *   <li>exp: 만료 시각(기본 15분). 짧게 두고 리프레시 토큰으로 새로 받는다. 세션 확인이 있어 끊을 수는 있지만,
 *       토큰에 담긴 역할 같은 정보는 다음 리프레시 때에야 DB 의 새 값으로 바뀌므로 짧을수록 좋다.</li>
 *   <li>jti: 토큰 고유 번호</li>
 * </ul>
 * 토큰은 서명만 되어 있고 암호화되지 않는다. 누구나 내용을 읽을 수 있으므로 비밀 정보를 넣지 않는다.
 */
@Service
public class JwtTokenService {

	public static final String ISSUER = "corebank";
	public static final String SESSION_ID_CLAIM = "sid";

	private final JwtEncoder encoder;
	private final Clock clock;
	private final Duration ttl;

	public JwtTokenService(JwtEncoder encoder, Clock clock,
			@Value("${corebank.auth.access-token-ttl:15m}") Duration ttl) {
		this.encoder = encoder;
		this.clock = clock;
		this.ttl = ttl;
	}

	public Duration ttl() {
		return ttl;
	}

	/**
	 * 관리자 토큰. 역할은 DB 에서 읽은 값이라, 토큰을 고쳐 역할을 올리면 서명이 깨진다.
	 * 직접 부르지 않고 세션을 만드는 AuthSessionService 를 거친다(세션 없는 토큰은 검증에서 거부된다).
	 */
	public IssuedToken issueAdminToken(String username, AdminRole role, String sessionId) {
		return issue(baseClaims(username, sessionId)
				.claim("scope", Actor.SCOPE_ADMIN)
				.claim(Actor.ROLE_CLAIM, role.name()));
	}

	/** 고객 토큰. 고객 번호(cid)는 서버가 정한 값이라 위조할 수 없다(서명이 깨진다). */
	public IssuedToken issueCustomerToken(Long customerId, String username, String name, String sessionId) {
		return issue(baseClaims(username, sessionId)
				.claim("scope", Actor.SCOPE_CUSTOMER)
				.claim(Actor.CUSTOMER_ID_CLAIM, customerId)
				.claim("name", name));
	}

	private JwtClaimsSet.Builder baseClaims(String username, String sessionId) {
		Instant now = clock.instant();
		return JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(username)
				.issuedAt(now)
				.expiresAt(now.plus(ttl))
				.id(UUID.randomUUID().toString())
				.claim(SESSION_ID_CLAIM, sessionId);
	}

	private IssuedToken issue(JwtClaimsSet.Builder builder) {
		JwtClaimsSet claims = builder.build();
		Instant expiresAt = claims.getExpiresAt();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, expiresAt);
	}

	public record IssuedToken(String value, Instant expiresAt) {
	}

}
