package com.corebank.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.corebank.audit.AuditContext;
import com.corebank.customer.CustomerService;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
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
 * <p>로그아웃 API 는 없다. 서버가 토큰을 저장하지 않으므로, 클라이언트가 토큰을 버리면 로그아웃이다.
 * (버린 토큰도 만료 전까지는 유효하다. 그래서 만료 시간을 짧게 둔다.)
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;
	private final CustomerService customerService;

	public AuthController(AuthService authService, CustomerService customerService) {
		this.authService = authService;
		this.customerService = customerService;
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
	 * @param accessToken Authorization: Bearer 헤더에 넣어 보낼 토큰
	 * @param expiresIn   남은 유효 시간(초)
	 * @param role        ADMIN 또는 CUSTOMER
	 * @param name        고객 이름. 관리자는 null
	 */
	public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt, String username,
			String role, String name) {

		static TokenResponse of(AuthService.LoginResult result) {
			return new TokenResponse(result.token().value(), "Bearer", JwtTokenService.TTL.toSeconds(),
					result.token().expiresAt(), result.username(), result.role(), result.name());
		}
	}

	/** @param customerId, name 고객 토큰일 때만 값이 있다 */
	public record MeResponse(String username, String role, Long customerId, String name, Instant expiresAt) {
	}

	/**
	 * POST /auth/login: 로그인. 관리자 계정이면 관리자 토큰(role=ADMIN), 아니면 고객 토큰(role=CUSTOMER).
	 * 실패하면 401 INVALID_CREDENTIALS.
	 */
	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		AuditContext.attemptedUsername(request.username());
		return TokenResponse.of(authService.login(request.username(), request.password()));
	}

	/** POST /auth/customer/signup: 고객 가입. 가입하면 바로 로그인된 것으로 보고 토큰을 준다. 아이디 중복은 409. */
	@PostMapping("/customer/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public TokenResponse signUp(@Valid @RequestBody SignUpRequest request) {
		AuditContext.attemptedUsername(request.username());
		return TokenResponse.of(
				authService.customerToken(customerService.signUp(request.username(), request.password(), request.name())));
	}

	/** POST /auth/customer/login: 고객으로만 로그인. 관리자 계정은 401 INVALID_CREDENTIALS. */
	@PostMapping("/customer/login")
	public TokenResponse customerLogin(@Valid @RequestBody LoginRequest request) {
		AuditContext.attemptedUsername(request.username());
		return TokenResponse.of(authService.customerLogin(request.username(), request.password()));
	}

	/** GET /auth/me: 지금 토큰의 주인. 화면이 새로 고침 뒤 토큰이 아직 유효한지 확인할 때 쓴다. */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		Actor actor = Actor.from(jwt);
		return new MeResponse(actor.username(), actor.role().name(), actor.customerId(),
				jwt.getClaimAsString("name"), jwt.getExpiresAt());
	}

}
