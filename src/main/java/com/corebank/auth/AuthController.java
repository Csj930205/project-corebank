package com.corebank.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.corebank.admin.AdminRole;
import com.corebank.audit.AuditContext;
import com.corebank.customer.CustomerService;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.corebank.common.ErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 로그인 API. 관리자와 고객 모두 /auth/login 하나로 로그인하고, 응답의 role 로 어느 쪽인지 알려 준다.
 * 고객 가입은 /auth/customer/signup. /auth/customer/login 은 고객으로만 로그인하는 주소로 남겨 둔다.
 *
 * <p>로그인하면 액세스 토큰은 본문으로, 리프레시 토큰은 쿠키로 준다(RefreshCookie). 액세스 토큰이 만료되면
 * POST /auth/refresh 로 새로 받고, POST /auth/logout 으로 세션을 끊는다.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;
	private final CustomerService customerService;
	private final AuthSessionService sessions;
	private final JwtTokenService tokenService;
	private final RefreshCookie refreshCookie;

	public AuthController(AuthService authService, CustomerService customerService, AuthSessionService sessions,
			JwtTokenService tokenService, RefreshCookie refreshCookie) {
		this.authService = authService;
		this.customerService = customerService;
		this.sessions = sessions;
		this.tokenService = tokenService;
		this.refreshCookie = refreshCookie;
	}

	public record LoginRequest(
			@NotBlank @Size(max = 50) String username,
			@NotBlank @Size(max = 200) String password) {
	}

	/**
	 * 고객 가입 요청.
	 *
	 * @param username 영문 소문자로 시작하는 4~30자(영문 소문자, 숫자, 밑줄). 대소문자가 섞이면 "Kim" 과 "kim" 이
	 *                 다른 계정이 되어 헷갈리므로 소문자만 받는다.
	 * @param password 10~100자. 길이만 본다(NIST 권고: 복잡도 규칙보다 길이가 낫다).
	 * @param name     실명. 계좌를 열면 예금주 이름이 된다.
	 */
	public record SignUpRequest(
			@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{3,29}") String username,
			@NotBlank @Size(min = 10, max = 100) String password,
			@NotBlank @Size(max = 100) String name) {
	}

	/**
	 * 리프레시 토큰은 본문에 넣지 않는다(HttpOnly 쿠키로만 준다). 화면 스크립트가 읽을 수 없어야 XSS 로 훔쳐 가지 못한다.
	 *
	 * @param accessToken      Authorization: Bearer 헤더에 넣어 보낼 토큰
	 * @param expiresIn        액세스 토큰의 남은 유효 시간(초)
	 * @param expiresAt        액세스 토큰 만료. 지나면 POST /auth/refresh
	 * @param refreshExpiresAt 리프레시 토큰 만료(쓰지 않고 30분). 이때까지 아무 요청이 없으면 다시 로그인해야 한다
	 * @param sessionExpiresAt 로그인 세션의 끝(로그인 뒤 12시간). 계속 써도 이때는 다시 로그인한다
	 * @param role             ADMIN 또는 CUSTOMER
	 * @param name             고객 이름. 관리자는 null
	 * @param adminRole        관리자 역할(TELLER, APPROVER, OPERATOR). 고객은 null. 화면이 메뉴와 버튼을 고르는 데 쓴다.
	 */
	public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
			Instant refreshExpiresAt, Instant sessionExpiresAt, String username, String role, String name,
			AdminRole adminRole) {

		static TokenResponse of(AuthSessionService.Issued issued, JwtTokenService tokenService) {
			return new TokenResponse(issued.value(), "Bearer", tokenService.ttl().toSeconds(),
					issued.accessToken().expiresAt(), issued.refreshExpiresAt(), issued.sessionExpiresAt(),
					issued.username(), issued.role(), issued.name(), issued.adminRole());
		}
	}

	/** @param customerId, name 고객 토큰일 때만 값이 있다. adminRole 은 관리자 토큰일 때만 */
	public record MeResponse(String username, String role, Long customerId, String name, AdminRole adminRole,
			Instant expiresAt) {
	}

	/**
	 * POST /auth/login: 로그인. 관리자 계정이면 관리자 토큰(role=ADMIN), 아니면 고객 토큰(role=CUSTOMER).
	 * 실패하면 401 INVALID_CREDENTIALS.
	 */
	@PostMapping("/login")
	public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
		AuditContext.attemptedUsername(request.username());
		return tokens(HttpStatus.OK, authService.login(request.username(), request.password()));
	}

	/** POST /auth/customer/signup: 고객 가입. 가입하면 바로 로그인된 것으로 보고 토큰을 준다. 아이디 중복은 409. */
	@PostMapping("/customer/signup")
	public ResponseEntity<TokenResponse> signUp(@Valid @RequestBody SignUpRequest request) {
		AuditContext.attemptedUsername(request.username());
		return tokens(HttpStatus.CREATED,
				authService.customerToken(customerService.signUp(request.username(), request.password(), request.name())));
	}

	/** POST /auth/customer/login: 고객으로만 로그인. 관리자 계정은 401 INVALID_CREDENTIALS. */
	@PostMapping("/customer/login")
	public ResponseEntity<TokenResponse> customerLogin(@Valid @RequestBody LoginRequest request) {
		AuditContext.attemptedUsername(request.username());
		return tokens(HttpStatus.OK, authService.customerLogin(request.username(), request.password()));
	}

	/**
	 * POST /auth/refresh: 리프레시 토큰 쿠키로 새 액세스 토큰과 새 리프레시 토큰(쿠키)을 받는다. 본문은 없다.
	 * 쿠키가 없거나 만료·끊긴 세션이면 401 INVALID_REFRESH_TOKEN, 이미 쓴 토큰이면 401 REFRESH_TOKEN_REUSED
	 * (둘 다 쿠키를 지운다. 화면은 로그인 화면으로 보낸다).
	 *
	 * <p>Authorization 헤더는 보지 않는다. 액세스 토큰이 만료돼서 부르는 주소이기 때문이다.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
		AuthSessionService.Issued issued = sessions.refresh(refreshToken);
		AuditContext.attemptedUsername(issued.username());
		return tokens(HttpStatus.OK, issued);
	}

	/**
	 * POST /auth/logout: 로그인 세션을 끊고 쿠키를 지운다. 그 세션의 액세스 토큰도 바로 401 이 된다.
	 * 쿠키가 없거나 이미 끊긴 세션이어도 204 다(로그아웃은 늘 성공한다).
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
		sessions.logout(refreshToken).ifPresent(AuditContext::attemptedUsername);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString()).build();
	}

	/**
	 * 리프레시 실패. 공통 에러 형식으로 401 을 주면서 쿠키도 지운다. 쓸모없어진 쿠키를 브라우저가 계속 들고 다니지 않게 한다.
	 * 이 컨트롤러 안의 @ExceptionHandler 는 공통 처리(ApiExceptionHandler)보다 먼저 쓰인다.
	 */
	@ExceptionHandler(InvalidRefreshTokenException.class)
	public ResponseEntity<ErrorResponse> invalidRefresh(InvalidRefreshTokenException e) {
		return refreshFailed("INVALID_REFRESH_TOKEN", e.getMessage());
	}

	@ExceptionHandler(RefreshTokenReusedException.class)
	public ResponseEntity<ErrorResponse> reusedRefresh(RefreshTokenReusedException e) {
		return refreshFailed("REFRESH_TOKEN_REUSED", e.getMessage());
	}

	private ResponseEntity<ErrorResponse> refreshFailed(String code, String message) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
				.body(new ErrorResponse(code, message));
	}

	private ResponseEntity<TokenResponse> tokens(HttpStatus status, AuthSessionService.Issued issued) {
		return ResponseEntity.status(status)
				.header(HttpHeaders.SET_COOKIE, refreshCookie.of(issued).toString())
				.body(TokenResponse.of(issued, tokenService));
	}

	/** GET /auth/me: 지금 토큰의 주인. 화면이 새로 고침 뒤 토큰이 아직 유효한지 확인할 때 쓴다. */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		Actor actor = Actor.from(jwt);
		return new MeResponse(actor.username(), actor.role().name(), actor.customerId(),
				jwt.getClaimAsString("name"), actor.adminRole(), jwt.getExpiresAt());
	}

}
