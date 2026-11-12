package com.corebank.admin;

import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import com.corebank.auth.LoginThrottle;
import com.corebank.account.Account;
import com.corebank.account.AccountResponse;
import com.corebank.account.AccountStatus;
import com.corebank.common.DateRange;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 API. SecurityConfig 가 /admin/** 에 관리자 토큰을, 변경 요청에는 맞는 역할을 요구하므로
 * 여기까지 온 요청은 이미 권한이 확인됐다.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

	private final AdminQueryService queryService;
	private final LoginThrottle loginThrottle;
	private final AdminUserService adminUserService;

	public AdminController(AdminQueryService queryService, LoginThrottle loginThrottle,
			AdminUserService adminUserService) {
		this.queryService = queryService;
		this.loginThrottle = loginThrottle;
		this.adminUserService = adminUserService;
	}

	/**
	 * 쪽 번호 방식 목록 응답. 스프링의 Page 를 그대로 JSON 으로 내보내면 내부 구조가 API 가 되어 버려 따로 정한다.
	 *
	 * @param page 0부터 시작하는 쪽 번호
	 */
	public record AccountPage(List<AccountResponse> content, int page, int size, long totalElements, int totalPages) {

		static AccountPage from(Page<Account> page) {
			return new AccountPage(page.map(AccountResponse::from).getContent(), page.getNumber(), page.getSize(),
					page.getTotalElements(), page.getTotalPages());
		}
	}

	/**
	 * GET /admin/accounts?query=&status=&openedFrom=&openedTo=&minBalance=&maxBalance=&page=0&size=20 : 계좌 목록(최신 개설순).
	 * query 는 계좌번호 앞자리 또는 예금주 이름. openedFrom, openedTo 는 개설일(YYYY-MM-DD), minBalance, maxBalance 는 잔액(원)이고
	 * 모두 양 끝을 포함한다. 시작이 끝보다 크면 빈 목록이다.
	 */
	@GetMapping("/accounts")
	public AccountPage accounts(
			@RequestParam(required = false) @Size(max = 100) String query,
			@RequestParam(required = false) AccountStatus status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openedFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openedTo,
			@RequestParam(required = false) @PositiveOrZero Long minBalance,
			@RequestParam(required = false) @PositiveOrZero Long maxBalance,
			@RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		var search = new AccountSearch(query, status, new DateRange(openedFrom, openedTo), minBalance, maxBalance);
		return AccountPage.from(queryService.searchAccounts(search, page, size));
	}

	/** 잠긴 로그인 목록 응답. 한꺼번에 수천 개가 잠길 일은 드물지만, 그래도 앞의 200개만 준다. */
	public record LoginLocks(List<LoginThrottle.LockedLogin> content) {
	}

	/** GET /admin/login-locks : 지금 잠긴 아이디, 곧 풀리는 순서. 가입하지 않은 아이디도 잠기므로 함께 나온다. */
	@GetMapping("/login-locks")
	public LoginLocks loginLocks() {
		return new LoginLocks(loginThrottle.locked(200));
	}

	/**
	 * POST /admin/login-locks/{username}/unlock : 로그인 잠금 해제. 창구에서 본인 확인을 한 뒤 잠금 시간을 기다리지 않고 푼다.
	 * 실패 횟수도 지워 처음부터 센다. 잠긴 적 없는 아이디여도 204 다(어떤 아이디가 있는지 알려 주지 않는다).
	 */
	@PostMapping("/login-locks/{username}/unlock")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unlockLogin(@PathVariable @Size(max = 50) String username) {
		loginThrottle.unlock(username);
	}

	/**
	 * 관리자 등록 요청. 아이디 규칙은 고객과 같고, 비밀번호는 첫 관리자(.env)와 같이 12자 이상이다.
	 *
	 * @param role 맡길 역할. 정하면 바꿀 수 없다.
	 */
	public record CreateAdminRequest(
			@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{3,29}") String username,
			@NotBlank @Size(min = 12, max = 100) String password,
			@NotNull AdminRole role) {
	}

	/**
	 * 비밀번호 해시는 내보내지 않는다.
	 *
	 * @param requestedBy 등록을 요청한 운영자. 서버 설정으로 만든 계정은 null
	 * @param decidedBy   승인·반려한 책임자. 기다리는 중이거나 서버 설정으로 만든 계정은 null
	 */
	public record AdminUserResponse(Long id, String username, AdminRole role, AdminUserStatus status,
			String requestedBy, String decidedBy, LocalDateTime decidedAt, LocalDateTime createdAt) {

		static AdminUserResponse from(AdminUser user) {
			return new AdminUserResponse(user.getId(), user.getUsername(), user.getRole(), user.getStatus(),
					user.getRequestedBy(), user.getDecidedBy(), user.getDecidedAt(), user.getCreatedAt());
		}
	}

	public record AdminUsers(List<AdminUserResponse> content) {
	}

	/** GET /admin/admins?status= : 관리자 목록(등록 순). status 가 없으면 전체 */
	@GetMapping("/admins")
	public AdminUsers admins(@RequestParam(required = false) AdminUserStatus status) {
		return new AdminUsers(adminUserService.list(status).stream().map(AdminUserResponse::from).toList());
	}

	/**
	 * POST /admin/admins {username, password, role} : 관리자 등록 요청(운영자). 201, status=PENDING.
	 * 책임자가 승인해야 로그인할 수 있다. 이미 쓰는 아이디면 409 USERNAME_TAKEN
	 */
	@PostMapping("/admins")
	public ResponseEntity<AdminUserResponse> requestAdmin(@Valid @RequestBody CreateAdminRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		AdminUser user = adminUserService.request(request.username(), request.password(), request.role(),
				jwt.getSubject());
		return ResponseEntity.created(URI.create("/admin/admins/" + user.getId())).body(AdminUserResponse.from(user));
	}

	/** POST /admin/admins/{id}/approval : 등록 승인(책임자). 이미 결정됐으면 409 ADMIN_ALREADY_DECIDED */
	@PostMapping("/admins/{id}/approval")
	public AdminUserResponse approveAdmin(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
		return AdminUserResponse.from(adminUserService.approve(id, jwt.getSubject()));
	}

	/** POST /admin/admins/{id}/rejection : 등록 반려(책임자) */
	@PostMapping("/admins/{id}/rejection")
	public AdminUserResponse rejectAdmin(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
		return AdminUserResponse.from(adminUserService.reject(id, jwt.getSubject()));
	}

	/** GET /admin/summary : 계좌 현황과 원장 점검 결과 */
	@GetMapping("/summary")
	public AdminSummary summary() {
		return queryService.summary();
	}

}
