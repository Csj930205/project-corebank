package com.corebank.auth;

import com.corebank.admin.AdminRole;
import com.corebank.admin.AdminUser;
import com.corebank.admin.AdminUserRepository;
import com.corebank.customer.Customer;
import com.corebank.customer.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 로그인 세션과 리프레시 토큰.
 *
 * <pre>
 * 로그인     세션 생성 + 액세스 토큰(15분, sid 포함) + 리프레시 토큰(쿠키, 쓰지 않고 30분)
 * 리프레시   리프레시 토큰을 쓴 것으로 표시하고, 새 액세스 토큰 + 새 리프레시 토큰(회전)
 * 로그아웃   세션을 끊는다. 그 세션의 액세스 토큰도 다음 요청부터 401(JwtConfig 의 세션 검증)
 * </pre>
 *
 * <p><b>왜 회전하나</b>: 리프레시 토큰은 오래 사는 열쇠라 새면 위험하다. 쓸 때마다 바꾸면 훔친 토큰을 쓰는 순간
 * 진짜 사용자와 도둑 중 한쪽은 "이미 쓴 토큰" 을 내밀게 된다. 그때 세션을 통째로 끊어 둘 다 다시 로그인하게 한다
 * (도둑은 비밀번호를 모른다). 이것을 재사용 탐지라고 한다.
 *
 * <p><b>유예 시간</b>: 같은 사용자의 탭 두 개가 거의 동시에 같은 토큰으로 리프레시하면 늦은 쪽도 "이미 쓴 토큰" 이 된다.
 * 바꾼 지 몇 초(기본 10초) 안에 다시 온 것은 세션을 끊지 않고 그 요청만 거부한다. 브라우저에는 이미 새 쿠키가 있으므로
 * 다시 시도하면 된다. 그 몇 초 안의 도둑 사용은 잡지 못하는 대신, 정상 사용자가 갑자기 로그아웃되는 일을 막는다.
 *
 * <p>시각은 앱 시계(Clock)로 정한다. 테스트가 시계를 옮겨 만료를 확인한다.
 */
@Service
public class AuthSessionService {

	private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);
	private static final SecureRandom RANDOM = new SecureRandom();
	/** 리프레시 토큰 원문 길이. 256비트 무작위라 맞혀서 찾을 수 없다 */
	private static final int TOKEN_BYTES = 32;

	private final AuthSessionRepository sessions;
	private final RefreshTokenRepository tokens;
	private final AdminUserRepository adminUsers;
	private final CustomerRepository customers;
	private final JwtTokenService tokenService;
	private final Clock clock;
	private final Duration refreshIdle;
	private final Duration sessionMax;
	private final Duration reuseGrace;

	public AuthSessionService(AuthSessionRepository sessions, RefreshTokenRepository tokens,
			AdminUserRepository adminUsers, CustomerRepository customers, JwtTokenService tokenService, Clock clock,
			@Value("${corebank.auth.refresh-token-idle:30m}") Duration refreshIdle,
			@Value("${corebank.auth.session-max:12h}") Duration sessionMax,
			@Value("${corebank.auth.refresh-reuse-grace:10s}") Duration reuseGrace) {
		this.sessions = sessions;
		this.tokens = tokens;
		this.adminUsers = adminUsers;
		this.customers = customers;
		this.tokenService = tokenService;
		this.clock = clock;
		this.refreshIdle = refreshIdle;
		this.sessionMax = sessionMax;
		this.reuseGrace = reuseGrace;
	}

	/**
	 * 발급 결과.
	 *
	 * @param refreshToken     리프레시 토큰 원문. 쿠키로만 내보내고 저장하지 않는다
	 * @param refreshExpiresAt 이 리프레시 토큰의 만료(쓰지 않고 30분). 화면은 이때 로그아웃한다
	 * @param refreshMaxAge    발급 시각부터 그 만료까지(쿠키 수명)
	 * @param sessionExpiresAt 세션의 절대 만료(로그인 뒤 12시간). 이 뒤로는 리프레시해도 안 된다
	 * @param name             고객 이름. 관리자는 null
	 * @param adminRole        관리자 역할. 고객은 null
	 */
	public record Issued(JwtTokenService.IssuedToken accessToken, String refreshToken, Instant refreshExpiresAt,
			Duration refreshMaxAge, Instant sessionExpiresAt, String username, String role, String name,
			AdminRole adminRole) {

		/** 액세스 토큰 문자열(Authorization: Bearer 에 넣는 값) */
		public String value() {
			return accessToken.value();
		}
	}

	/** 관리자 로그인 세션을 연다. 비밀번호 확인은 호출하는 쪽(AuthService)이 끝냈다. */
	@Transactional
	public Issued issueAdminToken(String username, AdminRole role) {
		LocalDateTime now = now();
		AuthSession session = sessions.save(AuthSession.admin(username, now, now.plus(sessionMax)));
		return issue(session, now, tokenService.issueAdminToken(username, role, session.getId()), null, role);
	}

	/** 고객 로그인 세션을 연다. */
	@Transactional
	public Issued issueCustomerToken(Long customerId, String username, String name) {
		LocalDateTime now = now();
		AuthSession session = sessions.save(AuthSession.customer(customerId, username, now, now.plus(sessionMax)));
		return issue(session, now, tokenService.issueCustomerToken(customerId, username, name, session.getId()), name,
				null);
	}

	/**
	 * 리프레시. 토큰 행(과 세션 행)을 잠그고 확인한 뒤 새 토큰 쌍을 준다.
	 *
	 * <p>역할·이름은 토큰에서 옮기지 않고 DB 에서 다시 읽는다. 그사이 관리자 계정이 로그인할 수 없는 상태가 됐으면
	 * 여기서 세션을 끊는다. 역할이 바뀌는 기능이 생겨도 다음 리프레시(최대 15분 뒤)부터 새 역할이 토큰에 들어간다.
	 *
	 * <p>noRollbackFor: 재사용을 찾으면 세션을 끊고 예외를 던진다. 보통 런타임 예외는 트랜잭션을 롤백해 그 "끊기" 도
	 * 사라지는데, 이 예외만은 롤백하지 않고 커밋하게 해 끊긴 기록이 남는다. 계정 비활성(ACCOUNT_INACTIVE)도 같다.
	 *
	 * @throws InvalidRefreshTokenException 없는 토큰, 만료, 끊긴 세션, 세션 만료, 계정 비활성
	 * @throws RefreshTokenReusedException  이미 쓴 토큰(유예 시간 뒤면 세션도 끊는다)
	 */
	@Transactional(noRollbackFor = {RefreshTokenReusedException.class, InvalidRefreshTokenException.class})
	public Issued refresh(String rawToken) {
		LocalDateTime now = now();
		RefreshToken token = findLocked(rawToken).orElseThrow(InvalidRefreshTokenException::new);
		AuthSession session = token.getSession();

		if (token.isUsed()) {
			if (token.getUsedAt().plus(reuseGrace).isAfter(now)) {
				// 거의 동시에 온 두 번째 요청(탭 두 개 등). 세션은 두고 이 요청만 거부한다.
				throw new RefreshTokenReusedException();
			}
			log.warn("리프레시 토큰 재사용: sessionId={}, username={}. 세션을 끊는다.", session.getId(), session.getUsername());
			session.revoke(AuthSession.RevokeReason.REUSE_DETECTED, now);
			throw new RefreshTokenReusedException();
		}
		if (!session.isActiveAt(now) || !now.isBefore(token.getExpiresAt())) {
			throw new InvalidRefreshTokenException();
		}

		JwtTokenService.IssuedToken access;
		String name = null;
		AdminRole role = null;
		if (session.getPrincipalType() == AuthSession.PrincipalType.ADMIN) {
			Optional<AdminUser> admin = adminUsers.findByUsername(session.getUsername()).filter(AdminUser::isActive);
			if (admin.isEmpty()) {
				session.revoke(AuthSession.RevokeReason.ACCOUNT_INACTIVE, now);
				throw new InvalidRefreshTokenException();
			}
			role = admin.get().getRole();
			access = tokenService.issueAdminToken(session.getUsername(), role, session.getId());
		} else {
			Optional<Customer> customer = customers.findById(session.getCustomerId());
			if (customer.isEmpty()) {
				session.revoke(AuthSession.RevokeReason.ACCOUNT_INACTIVE, now);
				throw new InvalidRefreshTokenException();
			}
			name = customer.get().getName();
			access = tokenService.issueCustomerToken(session.getCustomerId(), session.getUsername(), name,
					session.getId());
		}
		token.markUsed(now);
		session.refreshed(now);
		return issue(session, now, access, name, role);
	}

	/**
	 * 로그아웃. 리프레시 토큰이 가리키는 세션을 끊는다. 없는 토큰·이미 끊긴 세션이어도 조용히 끝낸다
	 * (로그아웃은 "이 브라우저의 로그인을 없애라" 라 실패할 이유가 없고, 토큰이 있는지 알려 줄 필요도 없다).
	 *
	 * @return 끊은 세션의 사용자. 감사 기록에 쓴다. 토큰이 없었으면 비어 있다
	 */
	@Transactional
	public Optional<String> logout(String rawToken) {
		LocalDateTime now = now();
		return findLocked(rawToken).map(token -> {
			token.getSession().revoke(AuthSession.RevokeReason.LOGOUT, now);
			return token.getSession().getUsername();
		});
	}

	/** 액세스 토큰의 세션이 살아 있는지. 요청마다 부른다(JwtConfig). */
	@Transactional(readOnly = true)
	public boolean isActive(String sessionId) {
		return sessionId != null && sessions.isActive(sessionId, now());
	}

	private Issued issue(AuthSession session, LocalDateTime now, JwtTokenService.IssuedToken access, String name,
			AdminRole role) {
		String raw = newRawToken();
		// 리프레시 토큰은 세션보다 오래 살 수 없다. 끝나기 직전 리프레시도 세션 만료에서 끊긴다.
		LocalDateTime refreshExpiresAt = min(now.plus(refreshIdle), session.getExpiresAt());
		tokens.save(RefreshToken.of(session, hash(raw), now, refreshExpiresAt));
		String principalRole = session.getPrincipalType() == AuthSession.PrincipalType.ADMIN
				? Actor.SCOPE_ADMIN
				: Actor.SCOPE_CUSTOMER;
		return new Issued(access, raw, instant(refreshExpiresAt), Duration.between(now, refreshExpiresAt),
				instant(session.getExpiresAt()),
				session.getUsername(), principalRole, name, role);
	}

	private Optional<RefreshToken> findLocked(String rawToken) {
		if (rawToken == null || rawToken.isBlank() || rawToken.length() > 100) {
			return Optional.empty();
		}
		return tokens.findByHashForUpdate(hash(rawToken));
	}

	/** URL·쿠키에 그대로 쓸 수 있는 문자(Base64 URL, 패딩 없음) 43자 */
	private static String newRawToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** SHA-256 16진수 64자. 같은 원문은 늘 같은 해시라 해시로 바로 찾을 수 있다 */
	static String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
		}
	}

	/** TIMESTAMP(6) 에 맞춰 마이크로초까지만(DbTime 과 같은 이유: 저장 전후 값이 달라지지 않게) */
	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
	}

	private Instant instant(LocalDateTime time) {
		return time.atZone(clock.getZone() == null ? ZoneId.systemDefault() : clock.getZone()).toInstant();
	}

	private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
		return a.isBefore(b) ? a : b;
	}

}
