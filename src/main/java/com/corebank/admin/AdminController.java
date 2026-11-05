package com.corebank.admin;

import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import com.corebank.auth.LoginThrottle;
import com.corebank.account.Account;
import com.corebank.account.AccountResponse;
import com.corebank.account.AccountStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 API. SecurityConfig 가 /admin/** 에 관리자 토큰을 요구하므로, 여기까지 온 요청은 이미 관리자다.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

	private final AdminQueryService queryService;
	private final LoginThrottle loginThrottle;

	public AdminController(AdminQueryService queryService, LoginThrottle loginThrottle) {
		this.queryService = queryService;
		this.loginThrottle = loginThrottle;
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

	/** GET /admin/accounts?query=&status=&page=0&size=20 : 계좌 목록(최신 개설순). query 는 계좌번호 앞자리 또는 예금주 이름. */
	@GetMapping("/accounts")
	public AccountPage accounts(
			@RequestParam(required = false) @Size(max = 100) String query,
			@RequestParam(required = false) AccountStatus status,
			@RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return AccountPage.from(queryService.searchAccounts(query, status, page, size));
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

	/** GET /admin/summary : 계좌 현황과 원장 점검 결과 */
	@GetMapping("/summary")
	public AdminSummary summary() {
		return queryService.summary();
	}

}
