package com.corebank.auth;

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
 *   <li>scope: 권한(ADMIN 또는 CUSTOMER). Spring Security 가 "SCOPE_ADMIN" 같은 권한으로 바꿔 접근을 판단한다.</li>
 *   <li>cid, name: 고객 토큰에만. 고객 번호와 이름. 계좌 소유자 확인은 cid 로 한다.</li>
 *   <li>exp: 만료 시각. 만료 전에 강제로 무효화할 수 없으므로 짧게(30분) 둔다.</li>
 *   <li>jti: 토큰 고유 번호. 나중에 강제 로그아웃(차단 목록)을 만들 때 쓴다.</li>
 * </ul>
 * 토큰은 서명만 되어 있고 암호화되지 않는다. 누구나 내용을 읽을 수 있으므로 비밀 정보를 넣지 않는다.
 */
@Service
public class JwtTokenService {

	public static final String ISSUER = "corebank";
	public static final Duration TTL = Duration.ofMinutes(30);

	private final JwtEncoder encoder;
	private final Clock clock;

	public JwtTokenService(JwtEncoder encoder, Clock clock) {
		this.encoder = encoder;
		this.clock = clock;
	}

	public IssuedToken issueAdminToken(String username) {
		return issue(baseClaims(username).claim("scope", Actor.SCOPE_ADMIN));
	}

	/** 고객 토큰. 고객 번호(cid)는 서버가 정한 값이라 위조할 수 없다(서명이 깨진다). */
	public IssuedToken issueCustomerToken(Long customerId, String username, String name) {
		return issue(baseClaims(username)
				.claim("scope", Actor.SCOPE_CUSTOMER)
				.claim(Actor.CUSTOMER_ID_CLAIM, customerId)
				.claim("name", name));
	}

	private JwtClaimsSet.Builder baseClaims(String username) {
		Instant now = clock.instant();
		return JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(username)
				.issuedAt(now)
				.expiresAt(now.plus(TTL))
				.id(UUID.randomUUID().toString());
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
